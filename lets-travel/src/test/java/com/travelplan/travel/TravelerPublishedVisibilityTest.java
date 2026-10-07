package com.travelplan.travel;

import com.sun.net.httpserver.HttpServer;
import com.travelplan.common.security.AccessTokenCodec;
import com.travelplan.common.security.Role;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "security.internal-service-token=test-internal"})
class TravelerPublishedVisibilityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpServer DEPENDENCIES = dependencies();
    @Autowired AccessTokenCodec codec;
    @Autowired TravelStore store;
    @Value("${local.server.port}") int port;

    @DynamicPropertySource
    static void dependencyUrls(DynamicPropertyRegistry registry) {
        String url = "http://127.0.0.1:" + DEPENDENCIES.getAddress().getPort();
        registry.add("auth.service-url", () -> url);
        registry.add("services.users.url", () -> url);
    }

    @AfterAll static void stopDependencies() { DEPENDENCIES.stop(0); }

    @Test void travelerSeesOnlyFuturePublishedNonFullListings() throws Exception {
        UUID managerId = UUID.randomUUID();
        String manager = token(managerId, Role.TRAVEL_MANAGER);
        String admin = token(UUID.randomUUID(), Role.ADMIN);
        String traveler = token(UUID.randomUUID(), Role.TRAVELER);
        UUID destination = createDestination(admin);
        Map<String, UUID> listings = new LinkedHashMap<>();
        listings.put("draft", create(manager, destination, "Draft", "2027-08-01", "2027-08-02"));
        listings.put("eligible", create(manager, destination, "Eligible", "2027-08-01", "2027-08-02"));
        publish(manager, listings.get("eligible"));
        listings.put("today", create(manager, destination, "Today", "2027-08-01", "2027-08-02"));
        publish(manager, listings.get("today"));
        updateDates(manager, destination, listings.get("today"), LocalDate.now().toString(), LocalDate.now().toString());
        listings.put("past", create(manager, destination, "Past", "2027-08-01", "2027-08-02"));
        publish(manager, listings.get("past"));
        updateDates(manager, destination, listings.get("past"), LocalDate.now().minusDays(2).toString(),
                LocalDate.now().minusDays(1).toString());
        listings.put("full", create(manager, destination, "Full", "2027-08-01", "2027-08-02"));
        publish(manager, listings.get("full"));
        store.addParticipant(listings.get("full"), UUID.randomUUID());
        store.addParticipant(listings.get("full"), UUID.randomUUID());
        listings.put("unpublished", create(manager, destination, "Unpublished", "2027-08-01", "2027-08-02"));
        publish(manager, listings.get("unpublished"));
        assertThat(send("DELETE", "/api/travels/" + listings.get("unpublished") + "/publication", manager, null).statusCode())
                .isEqualTo(200);

        JsonNode travelerPage = JSON.readTree(send("GET", "/api/travels", traveler, null).body());
        assertThat(travelerPage.get("items")).hasSize(1);
        assertThat(travelerPage.get("items").get(0).get("id").asString()).isEqualTo(listings.get("eligible").toString());
        for (Map.Entry<String, UUID> listing : listings.entrySet()) {
            int expected = listing.getKey().equals("eligible") ? 200 : 404;
            assertThat(send("GET", "/api/travels/" + listing.getValue(), traveler, null).statusCode())
                    .as(listing.getKey()).isEqualTo(expected);
            assertThat(send("GET", "/api/travels/" + listing.getValue(), manager, null).statusCode())
                    .as("owner " + listing.getKey()).isEqualTo(200);
            assertThat(send("GET", "/api/travels/" + listing.getValue(), admin, null).statusCode())
                    .as("admin " + listing.getKey()).isEqualTo(200);
        }
    }

    private UUID createDestination(String admin) throws Exception {
        var response = send("POST", "/api/destinations", admin,
                "{\"name\":\"Rhodes " + UUID.randomUUID() + "\",\"country\":\"GR\"}");
        return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
    }

    private UUID create(String manager, UUID destination, String name, String start, String end) throws Exception {
        var response = send("POST", "/api/travels", manager, travelJson(destination, name, start, end));
        assertThat(response.statusCode()).isEqualTo(201);
        return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
    }

    private void publish(String manager, UUID id) throws Exception {
        assertThat(send("POST", "/api/travels/" + id + "/publication", manager, "{}").statusCode()).isEqualTo(200);
    }

    private void updateDates(String manager, UUID destination, UUID id, String start, String end) throws Exception {
        assertThat(send("PUT", "/api/travels/" + id, manager, travelJson(destination, "Date changed", start, end)).statusCode())
                .isEqualTo(200);
    }

    private static String travelJson(UUID destination, String name, String start, String end) {
        return "{\"name\":\"" + name + "\",\"description\":\"Visibility fixture\",\"startDate\":\"" + start
                + "\",\"endDate\":\"" + end + "\",\"capacity\":2,\"price\":\"50.00\",\"currency\":\"EUR\","
                + "\"stops\":[{\"destinationId\":\"" + destination + "\",\"position\":0}],"
                + "\"activities\":[],\"accommodations\":[],\"transports\":[]}";
    }

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30)).method(method, publisher).header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private String token(UUID userId, Role role) {
        return codec.issue(userId, role, UUID.randomUUID()).value();
    }

    private static HttpServer dependencies() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/token-validation", exchange -> {
                try (exchange) { exchange.sendResponseHeaders(200, -1); }
            });
            server.createContext("/internal/managers", exchange -> {
                try (exchange) {
                    String id = exchange.getRequestURI().getPath().substring("/internal/managers/".length());
                    byte[] body = ("{\"managerId\":\"" + id + "\",\"displayName\":\"Owner\","
                            + "\"memberSince\":\"2025-01-01T00:00:00Z\"}")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            server.start();
            return server;
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
