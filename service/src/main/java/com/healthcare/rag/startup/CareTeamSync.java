package com.healthcare.rag.startup;

import com.healthcare.rag.graph.GraphService;
import com.healthcare.rag.opa.OpaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * On startup, mirror the Neo4j care-team edges into OPA's {@code data.care_team} document.
 *
 * <p>In production this would be a change-data-capture pipeline; for the PoC a refresh on
 * service start keeps the policy input consistent with the graph. The OPA bundle also ships
 * a static {@code care_team.json} fallback, so policy still works if this push is skipped.
 */
@Component
public class CareTeamSync implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CareTeamSync.class);

    private final GraphService graph;
    private final OpaClient opa;

    public CareTeamSync(GraphService graph, OpaClient opa) {
        this.graph = graph;
        this.opa = opa;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<Map<String, Object>> edges = graph.allCareEdges().stream()
                    .map(e -> Map.<String, Object>of(
                            "provider", e.provider(),
                            "patient", e.patient(),
                            "rel", e.rel()))
                    .toList();
            if (edges.isEmpty()) {
                log.warn("No care-team edges found in Neo4j; keeping OPA's static fallback data");
                return;
            }
            opa.pushCareTeam(edges);
        } catch (Exception e) {
            // Non-fatal: OPA has a static fallback bundle.
            log.warn("Care-team sync to OPA failed ({}); relying on static bundle data", e.toString());
        }
    }
}
