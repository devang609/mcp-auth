package com.healthcare.rag.graph;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Care-team graph queries over Neo4j (Bolt). The driver is injected directly — no OGM /
 * Spring Data Neo4j repositories — so the Cypher is explicit and reviewable.
 */
@Service
public class GraphService {

    public record CareTeamMember(String providerId, String name, String role, String relationship) {}
    public record SimilarCase(long fromPatient, long toPatient, double score) {}
    public record CareEdge(String provider, long patient, String rel) {}

    private final Driver driver;

    public GraphService(Driver driver) {
        this.driver = driver;
    }

    /** Everyone TREATING / CONSULTING_ON a patient, plus nurses via unit assignment. */
    public List<CareTeamMember> careTeam(long patientId) {
        String cypher = """
            MATCH (p:Patient {id: $pid})
            OPTIONAL MATCH (doc)-[r:TREATS|CONSULTS_ON]->(p)
            WITH p, collect({id: doc.id, name: doc.name, role: coalesce(doc.role,'physician'),
                             rel: type(r)}) AS docs
            OPTIONAL MATCH (p)-[:ADMITTED_TO]->(u:Unit)<-[:ASSIGNED_TO_UNIT]-(n:Nurse)
            WITH docs + collect({id: n.id, name: n.name, role: 'nurse',
                                 rel: 'ASSIGNED_TO_UNIT'}) AS members
            UNWIND members AS m
            WITH m WHERE m.id IS NOT NULL
            RETURN DISTINCT m.id AS id, m.name AS name, m.role AS role, m.rel AS rel
            """;
        try (Session s = driver.session()) {
            return s.run(cypher, Map.of("pid", patientId)).list(rec -> new CareTeamMember(
                    rec.get("id").asString(null),
                    rec.get("name").asString(null),
                    rec.get("role").asString(null),
                    rec.get("rel").asString(null)));
        }
    }

    /** SIMILAR_CASE edges for encounters belonging to the given patients. */
    public List<SimilarCase> similarCases(List<Long> patientIds, int limit) {
        if (patientIds.isEmpty()) return List.of();
        String cypher = """
            MATCH (e1:Encounter)-[r:SIMILAR_CASE]->(e2:Encounter)
            WHERE e1.patient_id IN $pids
            RETURN e1.patient_id AS from, e2.patient_id AS to, r.score AS score
            ORDER BY r.score DESC
            LIMIT $limit
            """;
        try (Session s = driver.session()) {
            return s.run(cypher, Map.of("pids", patientIds, "limit", limit))
                    .list(rec -> new SimilarCase(
                            rec.get("from").asLong(),
                            rec.get("to").asLong(),
                            rec.get("score").asDouble(0.0)));
        }
    }

    /** Read every care-team edge (used to seed OPA's data document at startup). */
    public List<CareEdge> allCareEdges() {
        String cypher = """
            MATCH (doc)-[r:TREATS|CONSULTS_ON]->(p:Patient)
            RETURN doc.id AS provider, p.id AS patient, type(r) AS rel
            """;
        try (Session s = driver.session()) {
            return s.run(cypher).list(rec -> new CareEdge(
                    rec.get("provider").asString(null),
                    rec.get("patient").asLong(),
                    rec.get("rel").asString(null)));
        }
    }
}
