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
class ManagerTravelOwnershipTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID INVALID_MANAGER = UUID.randomUUID();
    private static final HttpServer DEPENDENCIES = dependencies();
    @Autowired AccessTokenCodec codec;
    @Value("${local.server.port}") int port;

    @DynamicPropertySource
    static void dependencyUrls(DynamicPropertyRegistry registry) {
        String url = "http://127.0.0.1:" + DEPENDENCIES.getAddress().getPort();
        registry.add("auth.service-url", () -> url);
        registry.add("services.users.url", () -> url);
    }

    @AfterAll static void stopDependencies() { DEPENDENCIES.stop(0); }

    @Test void managersAreSelfScopedWhileAdminCanManageEveryListing() throws Exception {
        UUID managerOne = UUID.randomUUID();
        UUID managerTwo = UUID.randomUUID();
        String managerOneToken = token(managerOne, Role.TRAVEL_MANAGER);
        String managerTwoToken = token(managerTwo, Role.TRAVEL_MANAGER);
        String adminToken = token(UUID.randomUUID(), Role.ADMIN);
        String travelerToken = token(UUID.randomUUID(), Role.TRAVELER);
        UUID destinationId = createDestination(adminToken);

        HttpResponse<String> created = send("POST", "/api/travels", managerOneToken,
                travelJson(null, destinationId, "Manager one trip"));
        assertThat(created.statusCode()).isEqualTo(201);
        UUID travelId = UUID.fromString(JSON.readTree(created.body()).get("id").asString());
        assertThat(JSON.readTree(created.body()).get("managerId").asString()).isEqualTo(managerOne.toString());

        assertThat(send("GET", "/api/travels/" + travelId, managerTwoToken, null).statusCode()).isEqualTo(404);
        assertThat(send("PUT", "/api/travels/" + travelId, managerTwoToken,
                travelJson(null, destinationId, "Stolen")).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/api/travels/" + travelId + "/publication", managerTwoToken, "{}").statusCode())
                .isEqualTo(404);
        assertThat(send("DELETE", "/api/travels/" + travelId, managerTwoToken, null).statusCode()).isEqualTo(404);

        HttpResponse<String> ownerUpdate = send("PUT", "/api/travels/" + travelId, managerOneToken,
                travelJson(managerTwo, destinationId, "Owner update"));
        assertThat(ownerUpdate.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(ownerUpdate.body()).get("managerId").asString()).isEqualTo(managerOne.toString());
        assertThat(send("POST", "/api/travels/" + travelId + "/publication", managerOneToken, "{}").statusCode())
                .isEqualTo(200);
        assertThat(send("DELETE", "/api/travels/" + travelId + "/publication", managerOneToken, null).statusCode())
                .isEqualTo(200);

        HttpResponse<String> adminCreated = send("POST", "/api/travels", adminToken,
                travelJson(managerTwo, destinationId, "Manager two trip"));
        assertThat(adminCreated.statusCode()).isEqualTo(201);
        UUID secondId = UUID.fromString(JSON.readTree(adminCreated.body()).get("id").asString());
        assertThat(send("GET", "/api/travels/" + travelId, adminToken, null).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/travels/" + secondId, adminToken, null).statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(send("GET", "/api/travels", managerOneToken, null).body()).get("total").asLong())
                .isEqualTo(1);
        assertThat(JSON.readTree(send("GET", "/api/travels", managerTwoToken, null).body()).get("total").asLong())
                .isEqualTo(1);
        assertThat(JSON.readTree(send("GET", "/api/travels", adminToken, null).body()).get("total").asLong())
                .isGreaterThanOrEqualTo(2);
        HttpResponse<String> statistics = internalGet("/internal/managers/" + managerOne + "/travel-statistics");
        assertThat(statistics.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(statistics.body()).get("totalTravelCount").asLong()).isEqualTo(1);

        HttpResponse<String> missingManager = send("POST", "/api/travels", adminToken,
                travelJson(null, destinationId, "Missing manager"));
        assertThat(missingManager.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(missingManager.body()).get("code").asString()).isEqualTo("MANAGER_REQUIRED");
        HttpResponse<String> invalidManager = send("POST", "/api/travels", adminToken,
                travelJson(INVALID_MANAGER, destinationId, "Invalid manager"));
        assertThat(invalidManager.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(invalidManager.body()).get("code").asString()).isEqualTo("MANAGER_NOT_ACTIVE");
        assertThat(send("POST", "/api/travels", travelerToken,
                travelJson(null, destinationId, "Forbidden")).statusCode()).isEqualTo(403);

        assertThat(send("DELETE", "/api/travels/" + travelId, adminToken, null).statusCode()).isEqualTo(204);
        assertThat(send("DELETE", "/api/travels/" + secondId, adminToken, null).statusCode()).isEqualTo(204);
    }

    private UUID createDestination(String adminToken) throws Exception {
        HttpResponse<String> response = send("POST", "/api/destinations", adminToken,
                "{\"name\":\"Santorini " + UUID.randomUUID() + "\",\"country\":\"GR\"}");
        assertThat(response.statusCode()).isEqualTo(201);
        return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
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

    private HttpResponse<String> internalGet(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30)).header("X-Internal-Service-Token", "test-internal").GET().build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private String token(UUID userId, Role role) {
        return codec.issue(userId, role, UUID.randomUUID()).value();
    }

    static String travelJson(UUID managerId, UUID destinationId, String name) {
        String owner = managerId == null ? "" : "\"managerId\":\"" + managerId + "\",";
        return "{" + owner + "\"name\":\"" + name + "\",\"description\":\"Complete audit trip\","
                + "\"startDate\":\"2027-06-01\",\"endDate\":\"2027-06-03\",\"capacity\":2,"
                + "\"price\":\"120.50\",\"currency\":\"EUR\",\"stops\":[{\"destinationId\":\""
                + destinationId + "\",\"position\":0}],\"activities\":[],\"accommodations\":[],\"transports\":[]}";
    }

    private static HttpServer dependencies() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/token-validation", exchange -> {
                try (exchange) { exchange.sendResponseHeaders(200, -1); }
            });
            server.createContext("/internal/managers", exchange -> {
                try (exchange) {
                    UUID id = UUID.fromString(exchange.getRequestURI().getPath().substring("/internal/managers/".length()));
                    if (id.equals(INVALID_MANAGER)) {
                        exchange.sendResponseHeaders(409, -1);
                    } else {
                        byte[] body = ("{\"managerId\":\"" + id + "\",\"displayName\":\"Test Manager\","
                                + "\"memberSince\":\"2025-01-01T00:00:00Z\"}")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    }
                }
            });
            server.start();
            return server;
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
