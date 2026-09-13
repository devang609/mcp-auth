package com.healthcare.rag.audit;

import com.healthcare.rag.opa.OpaDecision;
import com.healthcare.rag.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * In-service audit trail. OPA's console decision logs are the canonical HIPAA-flavored
 * record; this bounded ring buffer lets the {@code audit_my_access} tool show a caller
 * their own recent decisions. Also emits a structured log line per decision (with the
 * decision id in MDC) so the two trails correlate.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int CAPACITY = 1000;

    private final Deque<AuditEntry> buffer = new ArrayDeque<>();

    public synchronized void record(UserContext user,
                                    String action,
                                    String resourceType,
                                    Object resourceId,
                                    OpaDecision decision) {
        AuditEntry entry = new AuditEntry(
                Instant.now(), user.id(), action, resourceType, resourceId,
                decision.allow(), decision.reason(), decision.decision_id(),
                decision.redactFields());

        buffer.addLast(entry);
        while (buffer.size() > CAPACITY) {
            buffer.removeFirst();
        }

        try {
            MDC.put("user", user.id());
            MDC.put("decision_id", String.valueOf(decision.decision_id()));
            log.info("AUDIT action={} resource_type={} resource_id={} allow={} reason='{}'",
                    action, resourceType, resourceId, decision.allow(), decision.reason());
        } finally {
            MDC.remove("user");
            MDC.remove("decision_id");
        }
    }

    /** Most-recent-first decisions for the given user. */
    public synchronized List<AuditEntry> recentFor(String userId, int limit) {
        List<AuditEntry> out = new ArrayList<>();
        var it = buffer.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            AuditEntry e = it.next();
            if (e.user().equals(userId)) {
                out.add(e);
            }
        }
        return out;
    }
}
