package com.travelplan.engagement;

import com.travelplan.common.RequestIds;
import com.travelplan.common.CircuitBreakers;
import com.travelplan.common.ServiceUrls;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Delivers outbox events to travel-service, which builds the recommendation history from them.
 * Unpublished rows are sent oldest first in batches; rows are marked published only after
 * travel-service answered, so a failure means the batch is sent again later. Events travel-service
 * rejects as unprocessable are parked with the reason, so one bad event never blocks the others;
 * admins list and resend them through {@link EventQueueController}.
 * travel-service ignores events it has already applied, so sending twice is harmless (for example
 * when two replicas of this service relay at the same time).
 */
@Component
class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int BATCH = 100;

    private final JdbcTemplate jdbc;
    private final String travelUrl;
    private final String serviceToken;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    OutboxRelay(ObjectProvider<JdbcTemplate> jdbc,
                @Value("${events.relay.target-url:}") String travelUrl,
                @Value("${security.internal-service-token:change-me-internal}") String serviceToken) {
        this.jdbc = jdbc.getIfAvailable();
        this.travelUrl = ServiceUrls.withoutTrailingSlashes(travelUrl);
        this.serviceToken = serviceToken;
    }

    @Scheduled(fixedDelayString = "${events.relay.interval:PT1S}", initialDelayString = "${events.relay.initial-delay:PT10S}")
    void scheduled() {
        if (jdbc == null || travelUrl.isBlank()) return;
        try {
            relay();
        } catch (RuntimeException failure) {
            log.warn("Outbox relay failed; events stay queued: {}", failure.toString());
        }
    }

    /** Sends one batch; returns how many events were delivered (parked ones included). */
    int relay() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT event_id, event_type, aggregate_id, aggregate_version, "
                + "occurred_at, trace_id, payload FROM outbox_event WHERE published_at IS NULL AND parked_at IS NULL "
                + "ORDER BY occurred_at, event_id LIMIT ?", BATCH);
        if (rows.isEmpty()) return 0;
        List<Map<String, Object>> events = rows.stream().map(OutboxRelay::envelope).toList();
        HttpRequest request = HttpRequest.newBuilder(URI.create(travelUrl + "/internal/events")).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("X-Internal-Service-Token", serviceToken)
                .header(RequestIds.HEADER, traceOf(rows.getFirst()))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(events))).build();
        HttpResponse<String> response = send(request);
        List<Object> ids = rows.stream().map(row -> row.get("event_id")).toList();
        if (response == null || response.statusCode() != 200) {
            jdbc.update("UPDATE outbox_event SET publish_attempts = publish_attempts + 1 WHERE event_id IN ("
                    + placeholders(ids.size()) + ")", ids.toArray());
            throw new IllegalStateException("travel-service answered " + (response == null ? "nothing" : response.statusCode()));
        }
        Timestamp now = Timestamp.from(Instant.now());
        Map<String, String> rejected = rejected(response.body());
        for (Map.Entry<String, String> parked : rejected.entrySet()) {
            log.warn("Event {} was rejected by travel-service and parked: {}", parked.getKey(), parked.getValue());
            jdbc.update("UPDATE outbox_event SET parked_at = ?, last_error = ?, publish_attempts = publish_attempts + 1 "
                    + "WHERE event_id = ?", now, truncate(parked.getValue()), UUID.fromString(parked.getKey()));
        }
        List<Object> delivered = ids.stream().filter(id -> !rejected.containsKey(String.valueOf(id))).toList();
        if (!delivered.isEmpty()) {
            Object[] arguments = new Object[delivered.size() + 1];
            arguments[0] = now;
            for (int i = 0; i < delivered.size(); i++) arguments[i + 1] = delivered.get(i);
            jdbc.update("UPDATE outbox_event SET published_at = ? WHERE event_id IN (" + placeholders(delivered.size()) + ")", arguments);
        }
        return rows.size();
    }

    /** The events travel-service could not apply, by ID, with the reason it gave. */
    private static Map<String, String> rejected(String body) {
        Map<String, String> rejected = new LinkedHashMap<>();
        try {
            JsonNode list = JSON.readTree(body).get("rejected");
            if (list != null) for (JsonNode item : list) rejected.put(item.get("eventId").asString(), item.path("reason").asString(""));
        } catch (RuntimeException unreadable) {
            log.warn("Could not read travel-service's event result; treating the batch as delivered: {}", unreadable.toString());
        }
        return rejected;
    }

    /** The ID of the request that caused the event, so its delivery shows up under the same ID in the logs. */
    private static String traceOf(Map<String, Object> row) {
        Object id = row.get("trace_id");
        return id != null && RequestIds.isSafe(id.toString()) ? id.toString() : RequestIds.current();
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static String truncate(String reason) {
        return reason.length() <= 500 ? reason : reason.substring(0, 500);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return CircuitBreakers.send(client, request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException unreachable) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static Map<String, Object> envelope(Map<String, Object> row) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", String.valueOf(row.get("event_id")));
        event.put("eventType", row.get("event_type"));
        event.put("aggregateId", String.valueOf(row.get("aggregate_id")));
        event.put("aggregateVersion", ((Number) row.get("aggregate_version")).longValue());
        event.put("occurredAt", String.valueOf(row.get("occurred_at")));
        event.put("traceId", traceOf(row));
        event.put("payload", JSON.readValue(String.valueOf(row.get("payload")), Map.class));
        return event;
    }
}
