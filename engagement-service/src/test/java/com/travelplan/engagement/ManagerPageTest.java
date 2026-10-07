package com.travelplan.engagement;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.travelplan.common.security.AccessTokenCodec;
import com.travelplan.common.security.Role;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("infra")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "security.internal-service-token=test-internal",
                "spring.datasource.url=jdbc:h2:mem:engagement-manager-page;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa", "spring.datasource.password=",
                "subscriptions.housekeeping-initial-delay=PT24H"})
class ManagerPageTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger USER_STATUS = new AtomicInteger(200);
    private static final AtomicInteger TRAVEL_STATUS = new AtomicInteger(200);
    private static final AtomicReference<String> USER_BODY = new AtomicReference<>();
    private static final AtomicReference<String> TRAVEL_BODY = new AtomicReference<>();
    private static final AtomicReference<String> USER_TOKEN = new AtomicReference<>();
    private static final AtomicReference<String> TRAVEL_TOKEN = new AtomicReference<>();
    private static final AtomicReference<String> USER_REQUEST_ID = new AtomicReference<>();
    private static final AtomicReference<String> TRAVEL_REQUEST_ID = new AtomicReference<>();
    private static final HttpServer DEPENDENCIES = dependencies();
    @Autowired AccessTokenCodec codec;
    @Autowired JdbcTemplate jdbc;
    @Value("${local.server.port}") int port;
    private UUID managerId;

    @DynamicPropertySource
    static void dependencyUrls(DynamicPropertyRegistry registry) {
        String url = "http://127.0.0.1:" + DEPENDENCIES.getAddress().getPort();
        registry.add("auth.service-url", () -> url);
        registry.add("services.users.url", () -> url);
        registry.add("services.travel.url", () -> url);
    }

    @BeforeEach void reset() {
        managerId = UUID.randomUUID();
        USER_STATUS.set(200);
        TRAVEL_STATUS.set(200);
        USER_BODY.set("{\"managerId\":\"" + managerId + "\",\"displayName\":\"Maria Manager\","
                + "\"memberSince\":\"2025-03-04T12:30:00Z\",\"email\":\"maria@example.com\","
                + "\"role\":\"TRAVEL_MANAGER\",\"passwordHash\":\"secret-hash\","
                + "\"passportNumber\":\"P1234567\"}");
        TRAVEL_BODY.set("{\"managerId\":\"" + managerId + "\",\"completedTravelCount\":7,"
                + "\"totalTravelCount\":11,\"reportContent\":\"private-report\","
                + "\"travelerEmails\":[\"t@example.com\"]}");
    }

    @AfterAll static void stopDependencies() { DEPENDENCIES.stop(0); }

    @Test void composesOnlySafeTruthfulPhaseTwoFieldsAndPropagatesHeaders() throws Exception {
        HttpResponse<String> response = get(managerId, Role.TRAVELER, "audit-trace-42");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode page = JSON.readTree(response.body());
        assertThat(page.get("managerId").asString()).isEqualTo(managerId.toString());
        assertThat(page.get("displayName").asString()).isEqualTo("Maria Manager");
        assertThat(page.get("memberSince").asString()).isEqualTo("2025-03-04T12:30:00Z");
        assertThat(page.get("completedTravels").asLong()).isEqualTo(7);
        assertThat(page.get("totalTravels").asLong()).isEqualTo(11);
        assertThat(page.get("averageRating").isNull()).isTrue();
        assertThat(page.get("ratingCount").asLong()).isZero();
        assertThat(page.get("recentRatings")).isEmpty();
        assertThat(page.get("reportCount").asLong()).isZero();
        assertThat(page.has("email")).isFalse();
        assertThat(page.has("passportNumber")).isFalse();
        assertThat(page.has("reportContent")).isFalse();
        assertThat(page.has("role")).isFalse();
        assertThat(page.has("passwordHash")).isFalse();
        assertThat(page.has("travelerEmails")).isFalse();
        assertThat(response.body()).doesNotContain("maria@example.com", "secret-hash", "P1234567", "private-report",
                "t@example.com");
        assertThat(USER_TOKEN.get()).isEqualTo("test-internal");
        assertThat(TRAVEL_TOKEN.get()).isEqualTo("test-internal");
        assertThat(USER_REQUEST_ID.get()).isEqualTo("audit-trace-42");
        assertThat(TRAVEL_REQUEST_ID.get()).isEqualTo("audit-trace-42");
    }

    @Test void showsRatingsWithoutAuthorsAndCountsOnlyUpheldReports() throws Exception {
        Fixtures.feedback(jdbc, managerId, "Crete", 5, "<script>alert(1)</script>", "2026-05-01T10:00:00Z");
        Fixtures.feedback(jdbc, managerId, "Rhodes", 4, null, "2026-06-01T10:00:00Z");
        Fixtures.feedback(jdbc, managerId, "Naxos", 4, "Fine", "2026-07-01T10:00:00Z");
        Fixtures.feedback(jdbc, UUID.randomUUID(), "Someone else's trip", 1, "Other manager", "2026-07-02T10:00:00Z");
        Fixtures.report(jdbc, "TRAVEL_MANAGER", managerId, "RESOLVED");
        Fixtures.report(jdbc, "TRAVEL_MANAGER", managerId, "RESOLVED");
        Fixtures.report(jdbc, "TRAVEL_MANAGER", managerId, "OPEN");
        Fixtures.report(jdbc, "TRAVEL_MANAGER", managerId, "DISMISSED");
        Fixtures.report(jdbc, "TRAVELER", managerId, "RESOLVED");

        HttpResponse<String> response = get(managerId, Role.TRAVELER, null);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode page = JSON.readTree(response.body());
        assertThat(page.get("averageRating").asDouble()).isEqualTo(4.3);
        assertThat(page.get("ratingCount").asLong()).isEqualTo(3);
        JsonNode recent = page.get("recentRatings");
        assertThat(recent).hasSize(3);
        assertThat(recent.get(0).get("travelTitle").asString()).isEqualTo("Naxos");
        assertThat(recent.get(2).get("comment").asString()).isEqualTo("<script>alert(1)</script>");
        assertThat(recent.get(1).get("comment").isNull()).isTrue();
        assertThat(page.get("reportCount").asLong()).isEqualTo(2);
        assertThat(response.body()).doesNotContain("Secret Author", "private allegation text", "Other manager", "reporterId");
    }

    @Test void allSignedInRolesCanReadButAnonymousCannot() throws Exception {
        for (Role role : Role.values()) assertThat(get(managerId, role, null).statusCode()).isEqualTo(200);
        HttpRequest request = HttpRequest.newBuilder(uri(managerId)).GET().build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }
    }

    @Test void mapsMissingManagerAndDependencyFailuresWithoutPartialData() throws Exception {
        USER_STATUS.set(404);
        HttpResponse<String> missing = get(managerId, Role.TRAVELER, null);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(missing.body()).get("code").asString()).isEqualTo("NOT_FOUND");
        USER_STATUS.set(409);
        assertThat(get(managerId, Role.TRAVELER, null).statusCode()).isEqualTo(404);

        USER_STATUS.set(500);
        assertDependencyUnavailable(get(managerId, Role.TRAVELER, null));
        USER_STATUS.set(200);
        TRAVEL_STATUS.set(500);
        assertDependencyUnavailable(get(managerId, Role.TRAVELER, null));
        TRAVEL_STATUS.set(200);
        TRAVEL_BODY.set("{\"managerId\":\"" + UUID.randomUUID() + "\",\"completedTravelCount\":1,"
                + "\"totalTravelCount\":1}");
        assertDependencyUnavailable(get(managerId, Role.TRAVELER, null));
    }

    @Test void rejectsMalformedUserResponses() throws Exception {
        String valid = USER_BODY.get();
        for (String body : new String[] {
                valid.replace(managerId.toString(), UUID.randomUUID().toString()),
                "{\"managerId\":\"" + managerId + "\",\"memberSince\":\"2025-03-04T12:30:00Z\"}",
                valid.replace("2025-03-04T12:30:00Z", "not-a-date"),
                "not json"}) {
            USER_BODY.set(body);
            assertDependencyUnavailable(get(managerId, Role.TRAVELER, null));
        }
    }

    @Test void rejectsMalformedTravelResponses() throws Exception {
        for (String counts : new String[] {
                "\"completedTravelCount\":5,\"totalTravelCount\":3",
                "\"completedTravelCount\":-1,\"totalTravelCount\":3",
                "\"completedTravelCount\":\"2\",\"totalTravelCount\":3"}) {
            TRAVEL_BODY.set("{\"managerId\":\"" + managerId + "\"," + counts + "}");
            assertDependencyUnavailable(get(managerId, Role.TRAVELER, null));
        }
        TRAVEL_BODY.set("not json");
        assertDependencyUnavailable(get(managerId, Role.TRAVELER, null));
        TRAVEL_BODY.set(null);
        TRAVEL_STATUS.set(404);
        assertDependencyUnavailable(get(managerId, Role.TRAVELER, null));
    }

    private static void assertDependencyUnavailable(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(JSON.readTree(response.body()).get("code").asString()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(response.body()).doesNotContain("Maria Manager");
    }

    private HttpResponse<String> get(UUID id, Role role, String requestId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(id))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token(role)).GET();
        if (requestId != null) request.header("X-Request-ID", requestId);
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private URI uri(UUID id) { return URI.create("http://127.0.0.1:" + port + "/api/managers/" + id); }

    private String token(Role role) { return codec.issue(UUID.randomUUID(), role, UUID.randomUUID()).value(); }

    private static HttpServer dependencies() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/token-validation", exchange -> {
                try (exchange) { exchange.sendResponseHeaders(200, -1); }
            });
            server.createContext("/internal/managers", exchange -> {
                if (exchange.getRequestURI().getPath().endsWith("/travel-statistics"))
                    respond(exchange, TRAVEL_STATUS.get(), TRAVEL_BODY.get(), TRAVEL_TOKEN, TRAVEL_REQUEST_ID);
                else respond(exchange, USER_STATUS.get(), USER_BODY.get(), USER_TOKEN, USER_REQUEST_ID);
            });
            server.start();
            return server;
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private static void respond(HttpExchange exchange, int status, String body,
                                AtomicReference<String> token, AtomicReference<String> requestId) {
        try (exchange) {
            token.set(exchange.getRequestHeaders().getFirst("X-Internal-Service-Token"));
            requestId.set(exchange.getRequestHeaders().getFirst("X-Request-ID"));
            if (status == 404 || body == null) {
                exchange.sendResponseHeaders(status, -1);
                return;
            }
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
