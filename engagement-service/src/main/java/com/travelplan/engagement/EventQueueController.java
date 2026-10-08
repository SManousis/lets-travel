package com.travelplan.engagement;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin view of the event outbox: how many events wait for delivery, and the parked ones travel-service
 * rejected. Resending a parked event is safe because travel-service ignores events it already applied
 * (docs/operations.md, "Parked events").
 */
@RestController
class EventQueueController {
    private final JdbcTemplate jdbc;

    EventQueueController(ObjectProvider<JdbcTemplate> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    @GetMapping("/api/admin/events/status")
    ResponseEntity<?> status() {
        if (jdbc == null) return unavailable();
        Map<String, Object> row = jdbc.queryForMap("SELECT "
                + "SUM(CASE WHEN published_at IS NULL AND parked_at IS NULL THEN 1 ELSE 0 END) AS pending, "
                + "SUM(CASE WHEN parked_at IS NOT NULL AND published_at IS NULL THEN 1 ELSE 0 END) AS parked, "
                + "MIN(CASE WHEN published_at IS NULL AND parked_at IS NULL THEN occurred_at END) AS oldest FROM outbox_event");
        return ResponseEntity.ok(new Status(count(row.get("pending")), count(row.get("parked")), instant(row.get("oldest"))));
    }

    @GetMapping("/api/admin/events/parked")
    ResponseEntity<?> parked() {
        if (jdbc == null) return unavailable();
        return ResponseEntity.ok(jdbc.query("SELECT event_id, event_type, aggregate_id, aggregate_version, occurred_at, parked_at, "
                + "last_error, publish_attempts FROM outbox_event WHERE parked_at IS NOT NULL AND published_at IS NULL "
                + "ORDER BY parked_at LIMIT 200", (rs, n) -> new Parked(rs.getObject("event_id", UUID.class),
                rs.getString("event_type"), rs.getObject("aggregate_id", UUID.class), rs.getLong("aggregate_version"),
                rs.getTimestamp("occurred_at").toInstant(), rs.getTimestamp("parked_at").toInstant(), rs.getString("last_error"),
                rs.getInt("publish_attempts"))));
    }

    /** Puts a parked event back in the queue, for example after the consumer was fixed. */
    @PostMapping("/api/admin/events/parked/{eventId}/retry")
    ResponseEntity<?> retry(@PathVariable UUID eventId) {
        if (jdbc == null) return unavailable();
        int changed = jdbc.update("UPDATE outbox_event SET parked_at = NULL WHERE event_id = ? AND parked_at IS NOT NULL "
                + "AND published_at IS NULL", eventId);
        return changed == 0 ? ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Error("NOT_FOUND", "No parked event with this ID"))
                : ResponseEntity.noContent().build();
    }

    private static ResponseEntity<Error> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new Error("DEPENDENCY_UNAVAILABLE", "The event store is unavailable"));
    }

    private static long count(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.time.OffsetDateTime offset) return offset.toInstant();
        return value instanceof Instant at ? at : null;
    }

    record Status(long pending, long parked, Instant oldestPendingAt) {}

    record Parked(UUID eventId, String eventType, UUID aggregateId, long aggregateVersion, Instant occurredAt, Instant parkedAt,
                  String error, int attempts) {}

    record Error(String code, String message, List<Object> fieldErrors, String traceId) {
        Error(String code, String message) {
            this(code, message, List.of(), "");
        }
    }
}
