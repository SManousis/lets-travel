package com.travelplan.travel;

import com.travelplan.common.audit.AuditLog;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * travel-service keeps its audit records as {@code AuditEvent} nodes next to the travels. Neo4j Community has no
 * way to forbid changes for one label, so the copy in the AUDIT log (shipped to the log collector) is the
 * tamper-evident one (docs/security.md).
 */
@Component
class Neo4jAuditSink implements AuditLog.AuditSink {
    private final ObjectProvider<Driver> driver;

    Neo4jAuditSink(ObjectProvider<Driver> driver) {
        this.driver = driver;
    }

    @Override
    public void write(AuditLog.AuditEntry entry) {
        Driver neo4j = driver.getIfAvailable();
        if (neo4j == null) return;
        Map<String, Object> row = new HashMap<>();
        row.put("id", entry.auditId().toString());
        row.put("at", entry.occurredAt().atOffset(ZoneOffset.UTC));
        row.put("actorId", entry.actorId() == null ? null : entry.actorId().toString());
        row.put("actorRole", entry.actorRole());
        row.put("action", entry.action());
        row.put("targetType", entry.targetType());
        row.put("targetId", entry.targetId());
        row.put("requestId", entry.requestId());
        row.put("details", entry.details());
        try (Session session = neo4j.session()) {
            session.executeWrite(tx -> tx.run("CREATE (a:AuditEvent) SET a = $row", Map.of("row", row)).consume());
        }
    }
}
