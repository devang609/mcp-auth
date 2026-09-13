package com.healthcare.rag.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * pgvector similarity search — the vector index lives in the same Postgres instance as the
 * source data (no separate vector store to keep in sync). Access control is enforced by
 * pushing the OPA-provided patient-id filter straight into the WHERE clause, so a user can
 * never retrieve a note for a patient outside their scope, even by semantic match.
 */
@Repository
public class VectorSearchRepository {

    /** One vector hit; {@code distance} is cosine distance (smaller = more similar). */
    public record NoteHit(String noteId, long patientId, String noteType,
                          String specialty, double distance) {}

    private final JdbcTemplate jdbc;

    public VectorSearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param vectorLiteral pgvector literal, e.g. {@code [0.1,0.2,...]}
     * @param allowedPatientIds concrete ids the caller may see (ignored when allPatients)
     * @param allPatients when true, no patient filter (billing-style "all")
     */
    public List<NoteHit> search(String vectorLiteral,
                                List<Long> allowedPatientIds,
                                boolean allPatients,
                                int limit) {

        if (!allPatients && allowedPatientIds.isEmpty()) {
            return List.of();
        }

        StringBuilder sql = new StringBuilder(
                "SELECT note_id, patient_id, note_type, specialty, " +
                "       (embedding <=> ?::vector) AS distance " +
                "FROM clinical_notes_embeddings ");

        List<Object> args = new ArrayList<>();
        args.add(vectorLiteral); // for the SELECT distance expression

        if (!allPatients) {
            String placeholders = String.join(",", allowedPatientIds.stream().map(x -> "?").toList());
            sql.append("WHERE patient_id IN (").append(placeholders).append(") ");
            args.addAll(allowedPatientIds);
        }

        sql.append("ORDER BY embedding <=> ?::vector ASC LIMIT ?");
        args.add(vectorLiteral); // for the ORDER BY
        args.add(limit);

        return jdbc.query(sql.toString(), (rs, rowNum) -> new NoteHit(
                rs.getString("note_id"),
                rs.getLong("patient_id"),
                rs.getString("note_type"),
                rs.getString("specialty"),
                rs.getDouble("distance")), args.toArray());
    }
}
