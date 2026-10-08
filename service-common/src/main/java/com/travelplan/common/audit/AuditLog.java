package com.travelplan.common.audit;

import com.travelplan.common.RequestIds;
import com.travelplan.common.security.AccessToken;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Records privileged and destructive actions: who (from the signed-in caller), what, on which record, when and
 * under which request ID. Every record goes to the {@code AUDIT} log and to each {@link AuditSink} the service
 * provides (an append-only database table, see docs/security.md). A sink that fails makes the action fail, so
 * an action never happens without its record.
 *
 * <p>Details must never contain passwords, tokens, card data or free text written by users.
 */
public class AuditLog {
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");
    private final String service;
    private final List<AuditSink> sinks;
    private final Clock clock;

    public AuditLog(String service, List<AuditSink> sinks, Clock clock) {
        this.service = service;
        this.sinks = List.copyOf(sinks);
        this.clock = clock;
    }

    public void record(String action, String targetType, Object targetId) {
        record(action, targetType, targetId, Map.of());
    }

    public void record(String action, String targetType, Object targetId, Map<String, ?> details) {
        UUID actorId = null;
        String actorRole = "SYSTEM";
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AccessToken caller) {
            actorId = caller.userId();
            actorRole = caller.role().name();
        }
        AuditEntry entry = new AuditEntry(UUID.randomUUID(), clock.instant(), service, actorId, actorRole, action, targetType,
                targetId == null ? null : targetId.toString(), RequestIds.current(), json(details));
        AUDIT.info("{\"audit\":\"{}\",\"service\":\"{}\",\"actor\":\"{}\",\"role\":\"{}\",\"target\":\"{}:{}\",\"details\":{}}",
                action, service, actorId, actorRole, targetType, entry.targetId(), entry.details());
        for (AuditSink sink : sinks) sink.write(entry);
    }

    static String json(Map<String, ?> details) {
        String body = details.entrySet().stream()
                .map(field -> quote(field.getKey()) + ":" + quote(String.valueOf(field.getValue())))
                .collect(Collectors.joining(",", "{", "}"));
        return body.length() <= 1000 ? body : body.substring(0, 997) + "...";
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    public record AuditEntry(UUID auditId, Instant occurredAt, String service, UUID actorId, String actorRole, String action,
                             String targetType, String targetId, String requestId, String details) {}

    /** Where audit records are kept besides the log. */
    public interface AuditSink {
        void write(AuditEntry entry);
    }
}
