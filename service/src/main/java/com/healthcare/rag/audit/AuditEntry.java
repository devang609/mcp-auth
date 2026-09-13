package com.healthcare.rag.audit;

import java.time.Instant;
import java.util.List;

/** One access-control decision, surfaced per-user by the {@code audit_my_access} tool. */
public record AuditEntry(
        Instant timestamp,
        String user,
        String action,
        String resourceType,
        Object resourceId,
        boolean allow,
        String reason,
        String decisionId,
        List<String> redactedFields) {
}
