package com.travelplan.engagement;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Writes an outbox event; callers do this inside the transaction that made the change. */
final class Outbox {
    private static final ObjectMapper JSON = new ObjectMapper();

    private Outbox() {}

    static void write(JdbcTemplate jdbc, String eventType, UUID aggregateId, long version, Instant at,
                      Map<String, Object> payload) {
        jdbc.update("INSERT INTO outbox_event (event_id, event_type, aggregate_id, aggregate_version, occurred_at, trace_id, payload) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), eventType, aggregateId, version, time(at),
                InternalCalls.requestId(), JSON.writeValueAsString(payload));
    }

    static OffsetDateTime time(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
