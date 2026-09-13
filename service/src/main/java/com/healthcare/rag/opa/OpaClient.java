package com.healthcare.rag.opa;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.healthcare.rag.config.AppProperties;
import com.healthcare.rag.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Policy decision point client. Every tool call funnels through {@link #decide} so the
 * access rule that fires is centralized in Rego, not scattered across three DB dialects.
 */
@Component
public class OpaClient {

    private static final Logger log = LoggerFactory.getLogger(OpaClient.class);

    private final RestClient http;
    private final AppProperties props;

    public OpaClient(RestClient.Builder builder, AppProperties props) {
        this.props = props;
        this.http = builder.baseUrl(props.opa().url()).build();
    }

    /** OPA wraps rule results as {@code {"result": {...}}}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpaResponse(OpaDecision result) {}

    public OpaDecision decide(UserContext user,
                              String action,
                              String resourceType,
                              Object resourceId,
                              String queryType,
                              int requestedCount) {

        Map<String, Object> userMap = new HashMap<>();
        userMap.put("id", user.id());
        userMap.put("role", user.role());
        userMap.put("department", user.department());
        userMap.put("units", user.units() == null ? List.of() : user.units());
        userMap.put("supervisor", user.supervisor());
        userMap.put("protocol", user.protocol());
        userMap.put("patient_id", user.patientId());

        Map<String, Object> context = new HashMap<>();
        context.put("query_type", queryType);
        context.put("timestamp", Instant.now().toString());
        context.put("requested_count", requestedCount);

        Map<String, Object> input = new HashMap<>();
        input.put("user", userMap);
        input.put("action", action);
        input.put("resource_type", resourceType);
        input.put("resource_id", resourceId);
        input.put("context", context);

        try {
            OpaResponse resp = http.post()
                    .uri(props.opa().decisionPath())
                    .body(Map.of("input", input))
                    .retrieve()
                    .body(OpaResponse.class);

            OpaDecision d = resp == null ? null : resp.result();
            if (d == null) {
                // Fail closed if OPA returns nothing.
                return new OpaDecision(false, List.of(), List.of(),
                        "policy engine returned no decision", "n/a");
            }
            log.debug("OPA decision user={} action={} type={} allow={} reason='{}' id={}",
                    user.id(), action, resourceType, d.allow(), d.reason(), d.decision_id());
            return d;
        } catch (Exception e) {
            log.error("OPA call failed; failing closed. user={} type={}: {}",
                    user.id(), resourceType, e.toString());
            return new OpaDecision(false, List.of(), List.of(),
                    "policy engine unavailable: " + e.getMessage(), "n/a");
        }
    }

    /** Push the current care-team edges into OPA's in-memory data document. */
    public void pushCareTeam(List<Map<String, Object>> edges) {
        http.put()
                .uri(props.opa().dataPath())
                .body(edges)
                .retrieve()
                .toBodilessEntity();
        log.info("Pushed {} care-team edges to OPA at {}", edges.size(), props.opa().dataPath());
    }
}
