package com.travelplan.engagement;

import com.sun.net.httpserver.HttpServer;
import com.travelplan.common.security.AccessTokenCodec;
import com.travelplan.common.security.Role;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
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
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Dashboards read from real tables (H2 with the production migrations), with role scoping over HTTP. */
@ActiveProfiles("infra")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:engagement-dashboards;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "management.server.port=0",
        "security.internal-service-token=test-internal",
        "subscriptions.housekeeping-initial-delay=PT24H",
        "events.relay.initial-delay=PT24H"
})
class DashboardApiTest {
    private static final AtomicReference<String> paymentTotals = new AtomicReference<>("[]");
    private static final HttpServer DEPENDENCIES = dependencies();
    private final JsonMapper json = JsonMapper.builder().build();
    private final UUID ana = UUID.randomUUID();
    private final UUID bo = UUID.randomUUID();
    private final UUID traveler = UUID.randomUUID();

    @Autowired JdbcTemplate jdbc;
    @Autowired AccessTokenCodec codec;
    @Value("${local.server.port}") int port;

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        String url = "http://127.0.0.1:" + DEPENDENCIES.getAddress().getPort();
        registry.add("services.users.url", () -> url);
        registry.add("services.payments.url", () -> url);
        registry.add("auth.service-url", () -> url);
    }

    @AfterAll static void stop() { DEPENDENCIES.stop(0); }

    @BeforeEach void anaSoldTwoPlacesAndOneWasRefunded() {
        jdbc.update("DELETE FROM subscription_history");
        jdbc.update("DELETE FROM feedback");
        jdbc.update("DELETE FROM subscription");
        UUID rome = UUID.randomUUID();
        UUID kept = Fixtures.subscription(jdbc, rome, traveler, ana, "COMPLETED", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 5), "UTC");
        history(kept, "PENDING_PAYMENT", "CONFIRMED", "2026-04-10T10:00:00Z");
        history(kept, "CONFIRMED", "COMPLETED", "2026-05-06T10:00:00Z");
        UUID refunded = Fixtures.subscription(jdbc, rome, UUID.randomUUID(), ana, "CANCELLED_BY_TRAVELER",
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 5), "UTC");
        history(refunded, "PENDING_PAYMENT", "CONFIRMED", "2026-04-11T10:00:00Z");
        history(refunded, "CONFIRMED", "CANCELLED_BY_TRAVELER", "2026-04-20T10:00:00Z");
        UUID unpaid = Fixtures.subscription(jdbc, rome, UUID.randomUUID(), ana, "CANCELLED_BY_TRAVELER",
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 5), "UTC");
        history(unpaid, "PENDING_PAYMENT", "CANCELLED_BY_TRAVELER", "2026-04-12T10:00:00Z");
        Fixtures.subscription(jdbc, UUID.randomUUID(), UUID.randomUUID(), bo, "CONFIRMED",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), "UTC");
    }

    @Test void paidAndRefundedTimesComeFromTheSubscriptionHistory() throws Exception {
        JsonNode admin = get("/api/dashboards/admin/travels?managerId=" + ana, Role.ADMIN, UUID.randomUUID());
        JsonNode rome = admin.path("items").get(0);
        // Fixture prices are 100.00 EUR: two paid, one refunded, one never paid.
        assertThat(rome.path("travelers").asInt()).isEqualTo(1);
        assertThat(rome.path("cancellations").asInt()).isEqualTo(1);
        assertThat(rome.path("income").get(0).path("amountMinor").asLong()).isEqualTo(10000);
        assertThat(rome.path("managerDisplayName").asString()).isEqualTo("Manager Name");
    }

    @Test void managersSeeOnlyTheirOwnDashboardAdminsAnyone() throws Exception {
        JsonNode own = get("/api/dashboards/manager", Role.TRAVEL_MANAGER, ana);
        assertThat(own.path("managerId").asString()).isEqualTo(ana.toString());
        assertThat(own.path("cancellationRate").asDouble()).isEqualTo(0.5);

        JsonNode asksForBo = get("/api/dashboards/manager?managerId=" + bo, Role.TRAVEL_MANAGER, ana);
        assertThat(asksForBo.path("managerId").asString()).as("a manager cannot view another manager").isEqualTo(ana.toString());

        JsonNode adminForBo = get("/api/dashboards/manager?managerId=" + bo, Role.ADMIN, UUID.randomUUID());
        assertThat(adminForBo.path("managerId").asString()).isEqualTo(bo.toString());
        assertThat(adminForBo.path("totalTravelers").asInt()).isEqualTo(1);
    }

    @Test void travelersSeeTheirOwnStatisticsAndPaymentMethods() throws Exception {
        JsonNode me = get("/api/dashboards/me", Role.TRAVELER, traveler);
        assertThat(me.path("completedTrips").asInt()).isEqualTo(1);
        assertThat(me.path("cancellations").asInt()).isZero();
        assertThat(me.path("preferredPaymentMethods").get(0).path("provider").asString()).isEqualTo("STRIPE");
        assertThat(me.path("asOf").asString()).isNotBlank();
    }

    @Test void reconciliationComparesSubscriptionIncomeWithPayments() throws Exception {
        paymentTotals.set("[{\"currency\":\"EUR\",\"succeededMinor\":10000,\"refundPendingMinor\":0,\"refundedMinor\":10000}]");
        JsonNode match = get("/api/dashboards/admin/reconciliation", Role.ADMIN, UUID.randomUUID());
        assertThat(match.get(0).path("status").asString()).isEqualTo("MATCH");
        assertThat(match.get(0).path("subscriptionNetMinor").asLong()).isEqualTo(10000);

        paymentTotals.set("[{\"currency\":\"EUR\",\"succeededMinor\":20000,\"refundPendingMinor\":0,\"refundedMinor\":0}]");
        JsonNode different = get("/api/dashboards/admin/reconciliation", Role.ADMIN, UUID.randomUUID());
        assertThat(different.get(0).path("status").asString()).isEqualTo("DIFFERENT");
        assertThat(different.get(0).path("difference").asLong()).isEqualTo(10000);
    }

    @Test void invalidRangesAreRejected() throws Exception {
        assertThat(status("/api/dashboards/admin?months=0", Role.ADMIN)).isEqualTo(400);
        assertThat(status("/api/dashboards/admin?months=25", Role.ADMIN)).isEqualTo(400);
        assertThat(status("/api/dashboards/admin?top=21", Role.ADMIN)).isEqualTo(400);
    }

    private void history(UUID subscription, String from, String to, String at) {
        jdbc.update("INSERT INTO subscription_history (history_id, subscription_id, from_status, to_status, actor_id, reason, occurred_at) "
                + "VALUES (?, ?, ?, ?, NULL, NULL, ?)", UUID.randomUUID(), subscription, from, to, OffsetDateTime.parse(at));
    }

    private JsonNode get(String path, Role role, UUID user) throws Exception {
        HttpResponse<String> response = send(path, role, user);
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        return json.readTree(response.body());
    }

    private int status(String path, Role role) throws Exception {
        return send(path, role, UUID.randomUUID()).statusCode();
    }

    private HttpResponse<String> send(String path, Role role, UUID user) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + codec.issue(user, role, UUID.randomUUID()).value()).GET().build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    /** Stands in for auth (sessions), user-service (manager names) and payment-service (usage, totals). */
    private static HttpServer dependencies() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    String path = exchange.getRequestURI().getPath();
                    String body;
                    if (path.startsWith("/internal/managers/")) {
                        String id = path.substring("/internal/managers/".length());
                        body = "{\"managerId\":\"" + id + "\",\"displayName\":\"Manager Name\",\"memberSince\":\"2026-01-01T00:00:00Z\"}";
                    } else if (path.endsWith("/payment-usage")) {
                        body = "[{\"provider\":\"STRIPE\",\"payments\":2},{\"provider\":\"PAYPAL\",\"payments\":1}]";
                    } else if (path.equals("/internal/payment-totals")) {
                        body = paymentTotals.get();
                    } else {
                        body = "{}";
                    }
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
