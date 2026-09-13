package com.healthcare.rag.opa;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The OPA policy output (docs/CONTRACT.md §5.2). {@code patientIdFilter} may contain the
 * sentinel {@code "*"} meaning "all patients" (billing).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpaDecision(
        boolean allow,
        List<Object> patient_id_filter,
        List<String> redact_fields,
        String reason,
        String decision_id) {

    /** True when the filter is the "all patients" sentinel. */
    public boolean isAllPatients() {
        return patient_id_filter != null
                && patient_id_filter.stream().anyMatch(v -> "*".equals(String.valueOf(v)));
    }

    /** Concrete numeric patient ids (empty when {@link #isAllPatients()}). */
    public List<Long> patientIds() {
        if (patient_id_filter == null || isAllPatients()) {
            return List.of();
        }
        return patient_id_filter.stream()
                .map(v -> Long.parseLong(String.valueOf(v).trim()))
                .toList();
    }

    public List<String> redactFields() {
        return redact_fields == null ? List.of() : redact_fields;
    }
}
