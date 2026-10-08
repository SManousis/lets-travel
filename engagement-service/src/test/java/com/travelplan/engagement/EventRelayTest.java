package com.travelplan.engagement;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Outbox events reach travel-service once, in order, and survive a travel-service outage. */
@ActiveProfiles("infra")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:engagement-relay;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "management.server.port=0",
        "security.internal-service-token=test-internal",
        "subscriptions.housekeeping-initial-delay=PT24H",
        // The test drives the relay itself.
        "events.relay.initial-delay=PT24H"
})
class EventRelayTest {
    private static final List<JsonNode> received = new CopyOnWriteArrayList<>();
    private static final AtomicInteger travelStatus = new AtomicInteger(200);
    private static final HttpServer TRAVEL = travelService();
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired OutboxRelay relay;
    @Autowired JdbcTemplate jdbc;
    @Value("${local.server.port}") int port;

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        registry.add("events.relay.target-url", () -> "http://127.0.0.1:" + TRAVEL.getAddress().getPort());
    }

    @AfterAll static void stop() { TRAVEL.stop(0); }

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM outbox_event");
        received.clear();
        travelStatus.set(200);
    }

    @Test void deliversEachEventOnceOldestFirstAndMarksItPublished() {
        UUID subscription = UUID.randomUUID();
        Outbox.write(jdbc, "SubscriptionConfirmed", subscription, 2, Instant.parse("2026-10-08T10:00:02Z"), Map.of("status", "CONFIRMED"));
        Outbox.write(jdbc, "SubscriptionReserved", subscription, 1, Instant.parse("2026-10-08T10:00:01Z"), Map.of("status", "PENDING_PAYMENT"));

        assertThat(relay.relay()).isEqualTo(2);

        assertThat(received).hasSize(1);
        JsonNode batch = received.getFirst();
        assertThat(batch.get(0).path("eventType").asString()).isEqualTo("SubscriptionReserved");
        assertThat(batch.get(1).path("eventType").asString()).isEqualTo("SubscriptionConfirmed");
        assertThat(batch.get(1).path("aggregateId").asString()).isEqualTo(subscription.toString());
        assertThat(batch.get(1).path("aggregateVersion").asLong()).isEqualTo(2);
        assertThat(batch.get(1).path("payload").path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL", Long.class)).isZero();

        assertThat(relay.relay()).isZero();
        assertThat(received).hasSize(1);
    }

    @Test void eventsStayQueuedWhileTravelServiceIsDownAndAreSentAfterwards() {
        Outbox.write(jdbc, "FeedbackSubmitted", UUID.randomUUID(), 1, Instant.now(), Map.of("rating", 4));
        travelStatus.set(503);

        assertThatThrownBy(relay::relay).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT publish_attempts FROM outbox_event", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL", Long.class)).isEqualTo(1);

        travelStatus.set(200);
        assertThat(relay.relay()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL", Long.class)).isZero();
    }

    @Test void historySnapshotListsConfirmedAndCompletedTripsWithRatingsOnly() throws Exception {
        UUID traveler = UUID.randomUUID();
        UUID manager = UUID.randomUUID();
        UUID completedTravel = UUID.randomUUID();
        UUID completed = Fixtures.subscription(jdbc, completedTravel, traveler, manager, "COMPLETED",
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 5), "UTC");
        jdbc.update("INSERT INTO feedback (feedback_id, subscription_id, travel_id, traveler_id, manager_id, travel_title, "
                        + "author_display_name, rating, comment, idempotency_key, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), completed, completedTravel, traveler, manager, "Trip", "Author", 4, null, UUID.randomUUID(),
                java.time.OffsetDateTime.parse("2026-05-06T10:00:00Z"));
        UUID confirmed = Fixtures.subscription(jdbc, UUID.randomUUID(), traveler, manager, "CONFIRMED",
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 5), "UTC");
        Fixtures.subscription(jdbc, UUID.randomUUID(), traveler, manager, "CANCELLED_BY_TRAVELER",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), "UTC");

        JsonNode page = json.readTree(get("/internal/recommendation-history?page=0&size=100", "test-internal").body());

        assertThat(page.path("items")).extracting(item -> item.path("subscriptionId").asString())
                .contains(completed.toString(), confirmed.toString());
        assertThat(page.path("items")).allSatisfy(item ->
                assertThat(item.path("status").asString()).isIn("CONFIRMED", "COMPLETED"));
        JsonNode rated = java.util.stream.StreamSupport.stream(page.path("items").spliterator(), false)
                .filter(item -> item.path("subscriptionId").asString().equals(completed.toString())).findFirst().orElseThrow();
        assertThat(rated.path("rating").asInt()).isEqualTo(4);
        assertThat(rated.path("travelerId").asString()).isEqualTo(traveler.toString());
        assertThat(get("/internal/recommendation-history", "wrong").statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> get(String path, String serviceToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30)).header("X-Internal-Service-Token", serviceToken).GET().build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    /** Records each delivered batch and answers with travelStatus. */
    private static HttpServer travelService() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/events", exchange -> {
                try (exchange) {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    boolean trusted = "test-internal".equals(exchange.getRequestHeaders().getFirst("X-Internal-Service-Token"));
                    int status = trusted ? travelStatus.get() : 403;
                    if (status == 200) received.add(JsonMapper.builder().build().readTree(body));
                    exchange.sendResponseHeaders(status, -1);
                }
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
