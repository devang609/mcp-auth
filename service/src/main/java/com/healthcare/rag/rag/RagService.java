package com.healthcare.rag.rag;

import com.healthcare.rag.audit.AuditEntry;
import com.healthcare.rag.audit.AuditService;
import com.healthcare.rag.domain.Encounter;
import com.healthcare.rag.domain.LabResult;
import com.healthcare.rag.domain.MedOrder;
import com.healthcare.rag.domain.Medication;
import com.healthcare.rag.domain.Patient;
import com.healthcare.rag.embedding.VoyageClient;
import com.healthcare.rag.graph.GraphService;
import com.healthcare.rag.mongo.ClinicalNote;
import com.healthcare.rag.mongo.MongoNotesService;
import com.healthcare.rag.opa.OpaClient;
import com.healthcare.rag.opa.OpaDecision;
import com.healthcare.rag.repo.*;
import com.healthcare.rag.security.UserContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Retrieval orchestration. Every public method:
 *   1. asks OPA for a decision (which is recorded to the audit trail),
 *   2. returns a structured denial if not allowed,
 *   3. otherwise queries the databases constrained by the OPA patient-id filter,
 *   4. applies field-level redaction from the decision.
 *
 * All cross-database access control therefore flows through one policy layer.
 */
@Service
public class RagService {

    private final OpaClient opa;
    private final AuditService audit;
    private final VoyageClient voyage;
    private final PatientRepository patients;
    private final EncounterRepository encounters;
    private final MedicationRepository medications;
    private final LabResultRepository labs;
    private final OrderRepository orders;
    private final VectorSearchRepository vectors;
    private final MongoNotesService notes;
    private final GraphService graph;

    public RagService(OpaClient opa, AuditService audit, VoyageClient voyage,
                      PatientRepository patients, EncounterRepository encounters,
                      MedicationRepository medications, LabResultRepository labs,
                      OrderRepository orders, VectorSearchRepository vectors,
                      MongoNotesService notes, GraphService graph) {
        this.opa = opa;
        this.audit = audit;
        this.voyage = voyage;
        this.patients = patients;
        this.encounters = encounters;
        this.medications = medications;
        this.labs = labs;
        this.orders = orders;
        this.vectors = vectors;
        this.notes = notes;
        this.graph = graph;
    }

    // ── Tool: search_patients ────────────────────────────────────────────────
    public Map<String, Object> searchPatients(UserContext user, String query, int limit) {
        OpaDecision d = authorize(user, "read", "patient", null, "search", limit);
        if (!d.allow()) return denial(d);

        float[] vec = voyage.embedQuery(query);
        List<VectorSearchRepository.NoteHit> hits = vectors.search(
                VoyageClient.toVectorLiteral(vec), d.patientIds(), d.isAllPatients(), limit * 3);

        // Distinct patient ids in semantic-rank order, capped at limit.
        List<Long> ranked = new ArrayList<>();
        for (var h : hits) {
            if (!ranked.contains(h.patientId())) ranked.add(h.patientId());
            if (ranked.size() >= limit) break;
        }

        Map<Long, Patient> byId = new LinkedHashMap<>();
        for (Patient p : patients.findByIdIn(ranked)) byId.put(p.getId(), p);

        List<Map<String, Object>> results = new ArrayList<>();
        for (Long pid : ranked) {
            Patient p = byId.get(pid);
            if (p != null) results.add(patientDto(p, d.redactFields()));
        }

        return Map.of(
                "allowed", true,
                "reason", d.reason(),
                "decision_id", d.decision_id(),
                "count", results.size(),
                "patients", results);
    }

    // ── Tool: get_patient_summary ────────────────────────────────────────────
    public Map<String, Object> getPatientSummary(UserContext user, long patientId) {
        OpaDecision d = authorize(user, "read", "patient", patientId, "get", 1);
        if (!d.allow()) return denial(d);
        if (!d.isAllPatients() && !d.patientIds().contains(patientId)) {
            return Map.of("allowed", false, "reason",
                    "patient " + patientId + " is outside your access scope",
                    "decision_id", d.decision_id());
        }

        Patient p = patients.findById(patientId).orElse(null);
        if (p == null) {
            return Map.of("allowed", true, "reason", d.reason(), "found", false);
        }

        List<Encounter> encs = encounters.findByPatientIdOrderByAdmitTimeDesc(patientId);
        List<Long> encIds = encs.stream().map(Encounter::getId).toList();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("allowed", true);
        summary.put("reason", d.reason());
        summary.put("decision_id", d.decision_id());
        summary.put("patient", patientDto(p, d.redactFields()));
        summary.put("encounters", encs.stream().map(this::encounterDto).toList());

        if (!encIds.isEmpty()) {
            summary.put("medications", medications.findByEncounterIdIn(encIds).stream()
                    .map(this::medicationDto).toList());
            summary.put("lab_results", labs.findByEncounterIdIn(encIds).stream()
                    .map(this::labDto).toList());
            summary.put("orders", orders.findByEncounterIdIn(encIds).stream()
                    .map(this::orderDto).toList());
        }

        // Clinical notes require notes:read (checked in the tool). Patient portal sees only
        // patient-viewable notes.
        boolean patientViewableOnly = "patient".equals(user.role());
        if (user.hasScope("notes:read")) {
            summary.put("notes", notes.findByPatient(patientId, patientViewableOnly, 25).stream()
                    .map(this::noteDto).toList());
        } else {
            summary.put("notes", "omitted: notes:read scope not granted");
        }

        summary.put("care_team", graph.careTeam(patientId));
        return summary;
    }

    // ── Tool: find_similar_cases ─────────────────────────────────────────────
    public Map<String, Object> findSimilarCases(UserContext user, String query, int limit) {
        OpaDecision d = authorize(user, "read", "note", null, "similar", limit);
        if (!d.allow()) return denial(d);

        float[] vec = voyage.embedQuery(query);
        List<VectorSearchRepository.NoteHit> hits = vectors.search(
                VoyageClient.toVectorLiteral(vec), d.patientIds(), d.isAllPatients(), limit);

        var texts = notes.findByNoteIds(hits.stream().map(VectorSearchRepository.NoteHit::noteId).toList());

        List<Map<String, Object>> matches = new ArrayList<>();
        List<Long> patientIds = new ArrayList<>();
        for (var h : hits) {
            patientIds.add(h.patientId());
            ClinicalNote n = texts.get(h.noteId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("note_id", h.noteId());
            m.put("patient_id", h.patientId());
            m.put("note_type", h.noteType());
            m.put("specialty", h.specialty());
            m.put("similarity", round(1.0 - h.distance()));
            m.put("snippet", n == null ? null : snippet(n.text()));
            matches.add(m);
        }

        return Map.of(
                "allowed", true,
                "reason", d.reason(),
                "decision_id", d.decision_id(),
                "matches", matches,
                "graph_similar_cases", graph.similarCases(patientIds, limit));
    }

    // ── Tool: query_care_team ────────────────────────────────────────────────
    public Map<String, Object> queryCareTeam(UserContext user, long patientId) {
        OpaDecision d = authorize(user, "read", "care_team", patientId, "get", 1);
        if (!d.allow()) return denial(d);
        if (!d.isAllPatients() && !d.patientIds().contains(patientId)) {
            return Map.of("allowed", false, "reason",
                    "patient " + patientId + " is outside your access scope",
                    "decision_id", d.decision_id());
        }
        return Map.of(
                "allowed", true,
                "reason", d.reason(),
                "decision_id", d.decision_id(),
                "patient_id", patientId,
                "care_team", graph.careTeam(patientId));
    }

    // ── Tool: audit_my_access ────────────────────────────────────────────────
    public Map<String, Object> auditMyAccess(UserContext user, int limit) {
        OpaDecision d = authorize(user, "read", "audit", null, "get", limit);
        if (!d.allow()) return denial(d);
        List<AuditEntry> entries = audit.recentFor(user.id(), limit);
        List<Map<String, Object>> log = new ArrayList<>();
        for (AuditEntry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("timestamp", e.timestamp() == null ? null : e.timestamp().toString());
            m.put("action", e.action());
            m.put("resource_type", e.resourceType());
            m.put("resource_id", e.resourceId());
            m.put("allow", e.allow());
            m.put("reason", e.reason());
            m.put("decision_id", e.decisionId());
            m.put("redacted_fields", e.redactedFields());
            log.add(m);
        }
        return Map.of(
                "allowed", true,
                "reason", d.reason(),
                "user", user.id(),
                "count", log.size(),
                "access_log", log);
    }

    // ── internals ────────────────────────────────────────────────────────────

    private OpaDecision authorize(UserContext user, String action, String resourceType,
                                  Object resourceId, String queryType, int requestedCount) {
        OpaDecision d = opa.decide(user, action, resourceType, resourceId, queryType, requestedCount);
        audit.record(user, action, resourceType, resourceId, d);
        return d;
    }

    private Map<String, Object> denial(OpaDecision d) {
        return Map.of("allowed", false, "reason", d.reason(), "decision_id", d.decision_id());
    }

    private Map<String, Object> patientDto(Patient p, List<String> redact) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("mrn", redactOr(redact, "mrn", p.getMrn()));
        m.put("name", redactOr(redact, "name", p.getName()));
        m.put("dob", redactOr(redact, "dob", p.getDob() == null ? null : p.getDob().toString()));
        m.put("sex", p.getSex());
        m.put("address", redactOr(redact, "address", p.getAddress()));
        m.put("ssn", redactOr(redact, "ssn", p.getSsn()));
        m.put("insurance_id", redactOr(redact, "insurance_id", p.getInsuranceId()));
        m.put("department", p.getDepartment());
        return m;
    }

    private Object redactOr(List<String> redact, String field, Object value) {
        return redact.contains(field) ? "[REDACTED]" : value;
    }

    private Map<String, Object> encounterDto(Encounter e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("admit_time", e.getAdmitTime() == null ? null : e.getAdmitTime().toString());
        m.put("discharge_time", e.getDischargeTime() == null ? null : e.getDischargeTime().toString());
        m.put("unit_id", e.getUnitId());
        m.put("chief_complaint", e.getChiefComplaint());
        return m;
    }

    private Map<String, Object> medicationDto(Medication x) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("drug", x.getDrug());
        m.put("dose", x.getDose());
        m.put("route", x.getRoute());
        m.put("frequency", x.getFrequency());
        return m;
    }

    private Map<String, Object> labDto(LabResult x) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lab_type", x.getLabType());
        m.put("value", x.getValue());
        m.put("unit", x.getUnit());
        m.put("reference_range", x.getReferenceRange());
        m.put("taken_at", x.getTakenAt() == null ? null : x.getTakenAt().toString());
        return m;
    }

    private Map<String, Object> orderDto(MedOrder x) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("order_type", x.getOrderType());
        m.put("ordering_provider_id", x.getOrderingProviderId());
        m.put("order_details", x.getOrderDetails());
        return m;
    }

    private Map<String, Object> noteDto(ClinicalNote n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("note_id", n.noteId());
        m.put("specialty", n.specialty());
        m.put("note_type", n.noteType());
        m.put("patient_viewable", n.patientViewable());
        m.put("collection", n.collection());
        m.put("text", n.text());
        return m;
    }

    private String snippet(String text) {
        if (text == null) return null;
        String t = text.strip();
        return t.length() <= 300 ? t : t.substring(0, 300) + "…";
    }

    private double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
