package com.healthcare.rag.security;

import java.util.List;

/**
 * The authenticated caller, projected from JWT claims into the shape OPA expects
 * (see docs/CONTRACT.md §4.1 / §5.1).
 */
public record UserContext(
        String id,           // preferred_username, e.g. "dr_smith"
        String role,         // attending_physician | resident | nurse | billing | researcher | patient
        String department,   // may be null
        List<String> units,  // may be empty
        String supervisor,   // residents only, else null
        String protocol,     // researchers only, else null
        String patientId,    // patient portal only, else null
        List<String> scopes  // granted OAuth scopes (without the SCOPE_ prefix)
) {
    public boolean hasScope(String scope) {
        return scopes != null && scopes.contains(scope);
    }
}
