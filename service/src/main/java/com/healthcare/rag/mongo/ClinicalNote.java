package com.healthcare.rag.mongo;

import java.util.Map;

/**
 * A clinical note projected from any of the four MongoDB collections (which have
 * intentionally varying schemas). {@code extra} carries the specialty-specific fields.
 */
public record ClinicalNote(
        String noteId,
        long patientId,
        String specialty,
        String noteType,
        String text,
        boolean patientViewable,
        String collection,
        Map<String, Object> extra) {
}
