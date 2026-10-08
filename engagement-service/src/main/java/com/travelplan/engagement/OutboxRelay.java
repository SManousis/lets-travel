package com.travelplan.engagement;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Delivers outbox events to travel-service, which builds the recommendation history from them.
 * Unpublished rows are sent oldest first in batches; a row is marked published only after
 * travel-service accepted the whole batch, so a failure means the batch is sent again later.
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

    /** Sends one batch; returns how many events were delivered. */
    int relay() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT event_id, event_type, aggregate_id, aggregate_version, "
                + "occurred_at, payload FROM outbox_event WHERE published_at IS NULL ORDER BY occurred_at, event_id LIMIT ?", BATCH);
        if (rows.isEmpty()) return 0;
        List<Map<String, Object>> events = rows.stream().map(OutboxRelay::envelope).toList();
        HttpRequest request = HttpRequest.newBuilder(URI.create(travelUrl + "/internal/events")).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("X-Internal-Service-Token", serviceToken)
                .header("X-Request-ID", InternalCalls.requestId())
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(events))).build();
        int status = send(request);
        List<Object> ids = rows.stream().map(row -> row.get("event_id")).toList();
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        if (status != 200) {
            jdbc.update("UPDATE outbox_event SET publish_attempts = publish_attempts + 1 WHERE event_id IN (" + placeholders + ")",
                    ids.toArray());
            throw new IllegalStateException("travel-service answered " + status);
        }
        Object[] arguments = new Object[ids.size() + 1];
        arguments[0] = Timestamp.from(Instant.now());
        for (int i = 0; i < ids.size(); i++) arguments[i + 1] = ids.get(i);
        jdbc.update("UPDATE outbox_event SET published_at = ? WHERE event_id IN (" + placeholders + ")", arguments);
        return rows.size();
    }

    private int send(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException unreachable) {
            return 503;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return 503;
        }
    }

    private static Map<String, Object> envelope(Map<String, Object> row) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", String.valueOf(row.get("event_id")));
        event.put("eventType", row.get("event_type"));
        event.put("aggregateId", String.valueOf(row.get("aggregate_id")));
        event.put("aggregateVersion", ((Number) row.get("aggregate_version")).longValue());
        event.put("occurredAt", String.valueOf(row.get("occurred_at")));
        event.put("payload", JSON.readValue(String.valueOf(row.get("payload")), Map.class));
        return event;
    }
}
