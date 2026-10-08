package com.travelplan.common.audit;

import java.sql.Timestamp;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Writes audit records to the service's {@code audit_event} table. On PostgreSQL a trigger rejects any UPDATE,
 * DELETE or TRUNCATE of that table, so records can only be added. Without a database (local runs without the
 * infra profile) records go to the log only.
 */
public class JdbcAuditSink implements AuditLog.AuditSink {
    private final ObjectProvider<JdbcTemplate> jdbc;

    public JdbcAuditSink(ObjectProvider<JdbcTemplate> jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void write(AuditLog.AuditEntry entry) {
        JdbcTemplate template = jdbc.getIfAvailable();
        if (template == null) return;
        template.update("INSERT INTO audit_event (audit_id, occurred_at, actor_id, actor_role, action, target_type, target_id, "
                        + "request_id, details) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                entry.auditId(), Timestamp.from(entry.occurredAt()), entry.actorId(), entry.actorRole(), entry.action(),
                entry.targetType(), entry.targetId(), entry.requestId(), entry.details());
    }
}
