package com.travelplan.travel;

import com.sun.net.httpserver.HttpServer;
import com.travelplan.common.testing.AuthorizationMatrix;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** The internal feed search-service indexes from, and the after-commit change notifications. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "security.internal-service-token=test-internal"})
class TravelSearchFeedTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpServer AUTH = AuthorizationMatrix.activeSessionsAuthServer();
    private static final LinkedBlockingQueue<String> NOTIFICATIONS = new LinkedBlockingQueue<>();
    private static final HttpServer SEARCH = searchStandIn();

    @Autowired TravelStore store;
    @Value("${local.server.port}") int port;

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        registry.add("auth.service-url", () -> "http://127.0.0.1:" + AUTH.getAddress().getPort());
        registry.add("services.search.url", () -> "http://127.0.0.1:" + SEARCH.getAddress().getPort());
    }

    @AfterAll static void stop() {
        AUTH.stop(0);
        SEARCH.stop(0);
    }

    @Test void everyTravelChangeNotifiesSearchWithTheServiceToken() throws Exception {
        var travel = draft("Notified trip");
        assertThat(nextNotification(travel.id())).isEqualTo(travel.id() + "|test-internal");
        store.publish(travel.id());
        assertThat(nextNotification(travel.id())).isEqualTo(travel.id() + "|test-internal");
        store.reserveSeat(travel.id(), UUID.randomUUID());
        nextNotification(travel.id());
        store.unpublish(travel.id());
        nextNotification(travel.id());
    }

    @Test void singleTravelSnapshotCarriesEveryDetailSearchIndexes() throws Exception {
        var travel = draft("Cretan coast");
        store.publish(travel.id());
        HttpResponse<String> response = get("/internal/search/travels/" + travel.id(), "test-internal");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("title").asString()).isEqualTo("Cretan coast");
        assertThat(body.get("bookable").asBoolean()).isTrue();
        assertThat(body.get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(body.get("destinations").get(0).get("name").asString()).startsWith("Chaniá");
        assertThat(body.get("destinations").get(0).get("country").asString()).isEqualTo("Greece");
        assertThat(body.get("activities").get(0).get("name").asString()).isEqualTo("Sea kayaking");
        assertThat(body.get("accommodations").get(0).get("name").asString()).isEqualTo("Harbour Hotel");
        assertThat(body.get("transports").get(0).get("mode").asString()).isEqualTo("FERRY");
        assertThat(body.get("remainingCapacity").asInt()).isEqualTo(10);
        assertThat(body.get("price").decimalValue()).isEqualByComparingTo("450.00");

        store.unpublish(travel.id());
        JsonNode hidden = JSON.readTree(get("/internal/search/travels/" + travel.id(), "test-internal").body());
        assertThat(hidden.get("bookable").asBoolean()).isFalse();
        assertThat(get("/internal/search/travels/" + UUID.randomUUID(), "test-internal").statusCode()).isEqualTo(404);
        assertThat(get("/internal/search/travels/" + travel.id(), "wrong").statusCode()).isEqualTo(403);
    }

    @Test void rebuildFeedListsOnlyBookableTravelsInPages() throws Exception {
        var open = draft("Open trip");
        store.publish(open.id());
        var hidden = draft("Draft trip");
        JsonNode first = JSON.readTree(get("/internal/search/travels?page=0&size=500", "test-internal").body());
        List<String> ids = first.get("items").valueStream().map(item -> item.get("travelId").asString()).toList();
        assertThat(ids).contains(open.id().toString()).doesNotContain(hidden.id().toString());
        assertThat(first.get("total").asInt()).isEqualTo(ids.size());
        assertThat(get("/internal/search/travels?size=501", "test-internal").statusCode()).isEqualTo(400);
    }

    private TravelDomain.Travel draft(String name) {
        var destination = store.createDestination("Chaniá " + UUID.randomUUID(), "Greece");
        LocalDate start = LocalDate.now().plusDays(30);
        return store.create(UUID.randomUUID(), name, "Island week by the sea", start, start.plusDays(6), 10,
                new BigDecimal("450.00"), "EUR",
                List.of(new TravelDomain.Stop(UUID.randomUUID(), destination.id(), 0, null, null)),
                List.of(new TravelDomain.Activity(UUID.randomUUID(), "Sea kayaking", "Paddle along the cliffs", start.plusDays(1))),
                List.of(new TravelDomain.Accommodation(UUID.randomUUID(), "Harbour Hotel", "1 Port Street", start, start.plusDays(6))),
                List.of(new TravelDomain.Transport(UUID.randomUUID(), "FERRY", "Piraeus", "Chania", start)),
                "Europe/Athens");
    }

    /** Waits for the next notification about this travel; other tests' notifications are skipped. */
    private static String nextNotification(UUID travelId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            String received = NOTIFICATIONS.poll(100, TimeUnit.MILLISECONDS);
            if (received != null && received.startsWith(travelId.toString())) return received;
        }
        throw new AssertionError("no search notification for " + travelId + " within 5 s");
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30)).header("X-Internal-Service-Token", token).GET().build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static HttpServer searchStandIn() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/search/index/travels/", exchange -> {
                try (exchange) {
                    String path = exchange.getRequestURI().getPath();
                    NOTIFICATIONS.add(path.substring(path.lastIndexOf('/') + 1) + "|"
                            + exchange.getRequestHeaders().getFirst("X-Internal-Service-Token"));
                    exchange.sendResponseHeaders(202, -1);
                }
            });
            server.start();
            return server;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
