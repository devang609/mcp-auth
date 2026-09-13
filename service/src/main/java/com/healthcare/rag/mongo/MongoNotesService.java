package com.healthcare.rag.mongo;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads clinical notes from the four specialty collections. Uses {@link MongoTemplate}
 * (not a typed repository) precisely because the documents' schemas differ per specialty —
 * a document store is justified here, and we read the common fields explicitly.
 */
@Service
public class MongoNotesService {

    private static final List<String> COLLECTIONS = List.of(
            "progress_notes", "radiology_reports", "pathology_reports", "discharge_summaries");

    private static final Set<String> COMMON = Set.of(
            "_id", "note_id", "patient_id", "specialty", "note_type", "text", "patient_viewable");

    private final MongoTemplate mongo;

    public MongoNotesService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** All notes for a patient, optionally restricted to patient-viewable ones. */
    public List<ClinicalNote> findByPatient(long patientId, boolean patientViewableOnly, int limit) {
        List<ClinicalNote> out = new ArrayList<>();
        for (String coll : COLLECTIONS) {
            Criteria c = Criteria.where("patient_id").is(patientId);
            if (patientViewableOnly) {
                c = c.and("patient_viewable").is(true);
            }
            Query q = new Query(c).limit(limit);
            for (Document d : mongo.find(q, Document.class, coll)) {
                out.add(map(d, coll));
                if (out.size() >= limit) return out;
            }
        }
        return out;
    }

    /** Fetch full note documents for a set of note ids (used to enrich vector hits). */
    public Map<String, ClinicalNote> findByNoteIds(Collection<String> noteIds) {
        Map<String, ClinicalNote> byId = new HashMap<>();
        if (noteIds.isEmpty()) return byId;
        for (String coll : COLLECTIONS) {
            Query q = new Query(Criteria.where("note_id").in(noteIds));
            for (Document d : mongo.find(q, Document.class, coll)) {
                ClinicalNote note = map(d, coll);
                byId.put(note.noteId(), note);
            }
        }
        return byId;
    }

    private ClinicalNote map(Document d, String coll) {
        Map<String, Object> extra = new HashMap<>();
        for (String key : d.keySet()) {
            if (!COMMON.contains(key)) {
                extra.put(key, d.get(key));
            }
        }
        String noteId = d.getString("note_id");
        if (noteId == null && d.get("_id") != null) {
            noteId = String.valueOf(d.get("_id"));
        }
        return new ClinicalNote(
                noteId,
                toLong(d.get("patient_id")),
                d.getString("specialty"),
                d.getString("note_type"),
                d.getString("text"),
                Boolean.TRUE.equals(d.get("patient_viewable")),
                coll,
                extra);
    }

    private long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v == null) return 0L;
        return Long.parseLong(String.valueOf(v));
    }
}
