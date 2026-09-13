package com.healthcare.rag.mcp;

import com.healthcare.rag.rag.RagService;
import com.healthcare.rag.security.CurrentUser;
import com.healthcare.rag.security.UserContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The five MCP tools. Each maps to an OAuth scope (least privilege) and delegates to
 * {@link RagService}, which enforces the OPA decision and per-field redaction.
 *
 * <p>Identity is read from the Spring Security context — the resource-server filter has
 * already validated the bearer JWT before this method runs on the same request thread.
 */
@Component
public class HealthcareTools {

    private final RagService rag;

    public HealthcareTools(RagService rag) {
        this.rag = rag;
    }

    @Tool(name = "search_patients",
          description = "Semantic + structured search across the patients the caller is "
                  + "authorized to see. Returns role-filtered, redacted patient records. "
                  + "Requires the patients:read scope.")
    public Object searchPatients(
            @ToolParam(description = "Natural-language search query, e.g. 'heart failure with reduced ejection fraction'")
            String query,
            @ToolParam(required = false, description = "Maximum number of patients to return (default 10)")
            Integer limit) {
        UserContext u = CurrentUser.require();
        Map<String, Object> missing = requireScope(u, "patients:read");
        if (missing != null) return missing;
        return rag.searchPatients(u, query, limit == null ? 10 : Math.max(1, limit));
    }

    @Tool(name = "get_patient_summary",
          description = "Full cross-database summary for one patient (demographics, encounters, "
                  + "medications, labs, orders, clinical notes, care team). Fields are redacted "
                  + "per the caller's role. Requires patients:read; notes are included only if "
                  + "notes:read is also granted.")
    public Object getPatientSummary(
            @ToolParam(description = "Numeric patient id, e.g. 10001")
            long patient_id) {
        UserContext u = CurrentUser.require();
        Map<String, Object> missing = requireScope(u, "patients:read");
        if (missing != null) return missing;
        return rag.getPatientSummary(u, patient_id);
    }

    @Tool(name = "find_similar_cases",
          description = "Vector-similarity search over clinical notes plus graph traversal of "
                  + "SIMILAR_CASE relationships, scoped to the caller's accessible patients. "
                  + "Requires the notes:read scope.")
    public Object findSimilarCases(
            @ToolParam(description = "Clinical description to find similar cases for")
            String query,
            @ToolParam(required = false, description = "Maximum number of matches (default 10)")
            Integer limit) {
        UserContext u = CurrentUser.require();
        Map<String, Object> missing = requireScope(u, "notes:read");
        if (missing != null) return missing;
        return rag.findSimilarCases(u, query, limit == null ? 10 : Math.max(1, limit));
    }

    @Tool(name = "query_care_team",
          description = "Return the care team (treating/consulting physicians and unit nurses) "
                  + "for a patient, from the Neo4j care graph. Requires the careteam:read scope.")
    public Object queryCareTeam(
            @ToolParam(description = "Numeric patient id, e.g. 10001")
            long patient_id) {
        UserContext u = CurrentUser.require();
        Map<String, Object> missing = requireScope(u, "careteam:read");
        if (missing != null) return missing;
        return rag.queryCareTeam(u, patient_id);
    }

    @Tool(name = "audit_my_access",
          description = "Return the caller's own recent access-control decisions (the HIPAA-style "
                  + "audit trail). Requires the audit:read scope.")
    public Object auditMyAccess(
            @ToolParam(required = false, description = "Maximum entries to return (default 20)")
            Integer limit) {
        UserContext u = CurrentUser.require();
        Map<String, Object> missing = requireScope(u, "audit:read");
        if (missing != null) return missing;
        return rag.auditMyAccess(u, limit == null ? 20 : Math.max(1, limit));
    }

    /** Least-privilege gate: returns a denial payload when the scope is absent, else null. */
    private Map<String, Object> requireScope(UserContext u, String scope) {
        if (u.hasScope(scope)) return null;
        return Map.of(
                "allowed", false,
                "reason", "missing required OAuth scope '" + scope + "'; the user did not grant it "
                        + "at the consent screen",
                "granted_scopes", u.scopes());
    }
}
