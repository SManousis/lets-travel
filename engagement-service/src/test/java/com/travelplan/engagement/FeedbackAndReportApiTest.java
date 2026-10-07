package com.travelplan.engagement;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.travelplan.common.security.AccessTokenCodec;
import com.travelplan.common.security.Role;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6 through the HTTP API with a real (H2, PostgreSQL mode) database and Flyway migrations.
 * user-service and travel-service are stand-ins with the same internal contracts.
 */
@ActiveProfiles("infra")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:engagement-feedback;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "management.server.port=0",
        "security.internal-service-token=test-internal",
        "subscriptions.housekeeping-initial-delay=PT24H"
})
class FeedbackAndReportApiTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");
    private static final SubscriptionApiTest.MutableClock CLOCK = new SubscriptionApiTest.MutableClock(NOW);
    /** travelId -> managerId of the travels the stand-in travel-service knows. */
    private static final Map<UUID, UUID> TRAVELS = new ConcurrentHashMap<>();
    private static final Set<UUID> MANAGERS = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> UNKNOWN_USERS = ConcurrentHashMap.newKeySet();
    private static final HttpServer DEPENDENCIES = dependencies();
    static final String SCRIPT = "<script>alert('xss')</script><img src=x onerror=alert(1)>";
    static final String SQL = "'; DROP TABLE feedback; -- ' OR '1'='1";

    @Autowired AccessTokenCodec codec;
    @Autowired JdbcTemplate jdbc;
    @Autowired SubscriptionService subscriptions;
    @Value("${local.server.port}") int port;

    private final UUID traveler = UUID.randomUUID();
    private final UUID manager = UUID.randomUUID();

    @TestConfiguration
    static class FixedClock {
        @Bean @Primary Clock testClock() { return CLOCK; }
    }

    @DynamicPropertySource
    static void dependencyUrls(DynamicPropertyRegistry registry) {
        String url = "http://127.0.0.1:" + DEPENDENCIES.getAddress().getPort();
        registry.add("auth.service-url", () -> url);
        registry.add("services.users.url", () -> url);
        registry.add("services.travel.url", () -> url);
        registry.add("services.payments.url", () -> url);
    }

    @AfterAll static void stop() { DEPENDENCIES.stop(0); }

    @BeforeEach void reset() {
        CLOCK.set(NOW);
        MANAGERS.add(manager);
    }

    // ---- Feedback eligibility -------------------------------------------------------------

    @Test void onlyFinishedParticipationsMayBeRatedAndOnlyOnce() throws Exception {
        UUID travel = travel(manager);
        assertCode(feedback(traveler, travel, UUID.randomUUID(), 5, "Great"), 409, "FEEDBACK_NOT_ELIGIBLE");

        Fixtures.subscription(jdbc, travel, traveler, manager, "CANCELLED_BY_TRAVELER",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 5), "UTC");
        assertCode(feedback(traveler, travel, UUID.randomUUID(), 5, "Great"), 409, "FEEDBACK_NOT_ELIGIBLE");

        Fixtures.subscription(jdbc, travel, traveler, manager, "COMPLETED",
                LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 15), "UTC");
        UUID key = UUID.randomUUID();
        HttpResponse<String> created = feedback(traveler, travel, key, 5, "  Great trip  ");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode body = JSON.readTree(created.body());
        assertThat(body.get("rating").asInt()).isEqualTo(5);
        assertThat(body.get("comment").asString()).isEqualTo("Great trip");
        assertThat(body.get("managerId").asString()).isEqualTo(manager.toString());
        assertThat(body.get("authorDisplayName").asString()).startsWith("User ");

        HttpResponse<String> retried = feedback(traveler, travel, key, 5, "Great trip");
        assertThat(retried.statusCode()).isEqualTo(201);
        assertThat(JSON.readTree(retried.body()).get("feedbackId").asString()).isEqualTo(body.get("feedbackId").asString());
        assertCode(feedback(traveler, travel, UUID.randomUUID(), 1, "Changed my mind"), 409, "FEEDBACK_ALREADY_SUBMITTED");

        String payload = jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_type = 'FeedbackSubmitted' "
                + "AND aggregate_id = ?", String.class, UUID.fromString(body.get("feedbackId").asString()));
        assertThat(payload).contains("\"rating\":5").doesNotContain("Great trip", "User ");
    }

    @Test void confirmedTravelBecomesEligibleAfterItsLastLocalDay() throws Exception {
        UUID travel = travel(manager);
        Fixtures.subscription(jdbc, travel, traveler, manager, "CONFIRMED",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), "Europe/Athens");
        // 23:00 in Athens on the last day: still travelling.
        CLOCK.set(Instant.parse("2026-10-07T20:00:00Z"));
        assertCode(feedback(traveler, travel, UUID.randomUUID(), 4, null), 409, "FEEDBACK_NOT_ELIGIBLE");
        // 00:30 the next day in Athens: the travel has ended, even before housekeeping marks it COMPLETED.
        CLOCK.set(Instant.parse("2026-10-07T21:30:00Z"));
        assertThat(feedback(traveler, travel, UUID.randomUUID(), 4, null).statusCode()).isEqualTo(201);
    }

    @Test void housekeepingMarksEndedConfirmedSubscriptionsCompleted() {
        UUID travel = travel(manager);
        UUID id = Fixtures.subscription(jdbc, travel, traveler, manager, "CONFIRMED",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), "Europe/Athens");
        CLOCK.set(Instant.parse("2026-10-07T20:00:00Z"));
        subscriptions.completeFinishedTravels();
        assertThat(status(id)).isEqualTo("CONFIRMED");
        CLOCK.set(Instant.parse("2026-10-07T21:30:00Z"));
        subscriptions.completeFinishedTravels();
        assertThat(status(id)).isEqualTo("COMPLETED");
    }

    @Test void feedbackInputIsValidated() throws Exception {
        UUID travel = travel(manager);
        assertCode(feedback(traveler, travel, UUID.randomUUID(), 6, null), 400, "VALIDATION_ERROR");
        assertCode(feedback(traveler, travel, UUID.randomUUID(), 0, null), 400, "VALIDATION_ERROR");
        assertCode(feedback(traveler, travel, UUID.randomUUID(), 3, "x".repeat(1001)), 400, "VALIDATION_ERROR");
        assertCode(send("POST", "/api/feedback", traveler, Role.TRAVELER, null,
                "{\"travelId\":\"" + travel + "\",\"rating\":3}"), 400, "VALIDATION_ERROR");
    }

    // ---- Feedback visibility ----------------------------------------------------------------

    @Test void managersSeeFeedbackOnTheirOwnTravelsAndAdminsSeeAll() throws Exception {
        UUID otherManager = UUID.randomUUID();
        UUID mine = travel(manager);
        UUID theirs = travel(otherManager);
        rate(traveler, mine, 5, "Lovely");
        rate(traveler, theirs, 2, "Meh");

        JsonNode own = page(send("GET", "/api/feedback", manager, Role.TRAVEL_MANAGER, null, null));
        assertThat(own.get("totalElements").asLong()).isOne();
        assertThat(own.get("items").get(0).get("comment").asString()).isEqualTo("Lovely");
        assertThat(page(send("GET", "/api/feedback?travelId=" + mine, manager, Role.TRAVEL_MANAGER, null, null))
                .get("totalElements").asLong()).isOne();
        // Another manager's travel is not revealed, and managerId cannot widen a manager's scope.
        assertCode(send("GET", "/api/feedback?travelId=" + theirs, manager, Role.TRAVEL_MANAGER, null, null), 404, "NOT_FOUND");
        assertThat(page(send("GET", "/api/feedback?managerId=" + otherManager, manager, Role.TRAVEL_MANAGER, null, null))
                .get("items").get(0).get("comment").asString()).isEqualTo("Lovely");

        JsonNode admin = page(send("GET", "/api/feedback?managerId=" + otherManager, UUID.randomUUID(), Role.ADMIN, null, null));
        assertThat(admin.get("totalElements").asLong()).isOne();
        assertThat(admin.get("items").get(0).get("comment").asString()).isEqualTo("Meh");
        assertThat(send("GET", "/api/feedback", traveler, Role.TRAVELER, null, null).statusCode()).isEqualTo(403);

        JsonNode history = page(send("GET", "/api/feedback/mine", traveler, Role.TRAVELER, null, null));
        assertThat(history.get("totalElements").asLong()).isEqualTo(2);
        assertThat(page(send("GET", "/api/feedback/mine", UUID.randomUUID(), Role.TRAVELER, null, null))
                .get("totalElements").asLong()).isZero();
    }

    // ---- Reports ---------------------------------------------------------------------------

    @Test void reportsRejectSelfInvalidTargetsAndDuplicates() throws Exception {
        assertCode(report(traveler, "TRAVELER", traveler, UUID.randomUUID()), 400, "SELF_REPORT");
        assertCode(report(manager, "TRAVEL_MANAGER", manager, UUID.randomUUID()), 400, "SELF_REPORT");
        assertCode(report(manager, "TRAVEL", travel(manager), UUID.randomUUID()), 400, "SELF_REPORT");
        assertCode(report(traveler, "TRAVEL_MANAGER", UUID.randomUUID(), UUID.randomUUID()), 404, "NOT_FOUND");
        assertCode(report(traveler, "TRAVEL", UUID.randomUUID(), UUID.randomUUID()), 404, "NOT_FOUND");
        UUID ghost = UUID.randomUUID();
        UNKNOWN_USERS.add(ghost);
        assertCode(report(traveler, "TRAVELER", ghost, UUID.randomUUID()), 404, "NOT_FOUND");
        assertCode(send("POST", "/api/reports", traveler, Role.TRAVELER, UUID.randomUUID(),
                "{\"targetType\":\"ROBOT\",\"targetId\":\"" + manager + "\",\"category\":\"OTHER\",\"description\":\"Long enough text\"}"),
                400, "VALIDATION_ERROR");
        assertCode(send("POST", "/api/reports", traveler, Role.TRAVELER, UUID.randomUUID(),
                "{\"targetType\":\"TRAVELER\",\"targetId\":\"" + manager + "\",\"category\":\"OTHER\",\"description\":\"short\"}"),
                400, "VALIDATION_ERROR");

        UUID key = UUID.randomUUID();
        HttpResponse<String> filed = report(traveler, "TRAVEL_MANAGER", manager, key);
        assertThat(filed.statusCode()).as(filed.body()).isEqualTo(201);
        JsonNode body = JSON.readTree(filed.body());
        assertThat(body.get("status").asString()).isEqualTo("OPEN");
        assertThat(body.get("targetDisplayName").asString()).isEqualTo("Manager " + manager.toString().substring(0, 8));
        assertThat(JSON.readTree(report(traveler, "TRAVEL_MANAGER", manager, key).body()).get("reportId").asString())
                .isEqualTo(body.get("reportId").asString());
        assertCode(report(traveler, "TRAVEL_MANAGER", manager, UUID.randomUUID()), 409, "DUPLICATE_REPORT");
        // A travel and a fellow traveler can be reported too.
        assertThat(report(traveler, "TRAVEL", travel(manager), UUID.randomUUID()).statusCode()).isEqualTo(201);
        assertThat(report(traveler, "TRAVELER", UUID.randomUUID(), UUID.randomUUID()).statusCode()).isEqualTo(201);

        String payload = jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_type = 'ReportFiled' AND aggregate_id = ?",
                String.class, UUID.fromString(body.get("reportId").asString()));
        assertThat(payload).contains("\"category\":\"FRAUD\"").doesNotContain("Took the money");
    }

    @Test void travelersMayFileAtMostTenReportsPerDay() throws Exception {
        for (int i = 0; i < ReportService.DAILY_LIMIT; i++)
            assertThat(report(traveler, "TRAVELER", UUID.randomUUID(), UUID.randomUUID()).statusCode()).isEqualTo(201);
        assertCode(report(traveler, "TRAVELER", UUID.randomUUID(), UUID.randomUUID()), 429, "TOO_MANY_REPORTS");
        CLOCK.set(NOW.plus(Duration.ofHours(25)));
        assertThat(report(traveler, "TRAVELER", UUID.randomUUID(), UUID.randomUUID()).statusCode()).isEqualTo(201);
    }

    @Test void reportContentIsVisibleOnlyToTheReporterAndAdmins() throws Exception {
        String id = JSON.readTree(report(traveler, "TRAVEL_MANAGER", manager, UUID.randomUUID()).body()).get("reportId").asString();

        JsonNode own = page(send("GET", "/api/reports", traveler, Role.TRAVELER, null, null));
        assertThat(own.get("totalElements").asLong()).isOne();
        assertThat(own.get("items").get(0).get("description").asString()).isEqualTo("Took the money and vanished");
        assertThat(own.get("items").get(0).has("reporterId")).isFalse();
        assertThat(page(send("GET", "/api/reports", manager, Role.TRAVEL_MANAGER, null, null)).get("totalElements").asLong()).isZero();
        assertThat(send("GET", "/api/admin/reports/" + id, manager, Role.TRAVEL_MANAGER, null, null).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/api/admin/reports", traveler, Role.TRAVELER, null, null).statusCode()).isEqualTo(403);

        JsonNode detail = JSON.readTree(send("GET", "/api/admin/reports/" + id, UUID.randomUUID(), Role.ADMIN, null, null).body());
        assertThat(detail.get("reporterId").asString()).isEqualTo(traveler.toString());
        assertThat(detail.get("description").asString()).isEqualTo("Took the money and vanished");
    }

    // ---- Moderation ------------------------------------------------------------------------

    @Test void adminsWorkTheQueueWithAnImmutableHistory() throws Exception {
        UUID admin = UUID.randomUUID();
        UUID reported = UUID.randomUUID();
        UUID travel = travel(manager);
        Fixtures.subscription(jdbc, travel, reported, manager, "CONFIRMED", LocalDate.of(2026, 12, 1),
                LocalDate.of(2026, 12, 5), "UTC");
        String first = JSON.readTree(report(traveler, "TRAVELER", reported, UUID.randomUUID()).body()).get("reportId").asString();
        CLOCK.set(NOW.plusSeconds(60));
        String second = JSON.readTree(report(UUID.randomUUID(), "TRAVELER", reported, UUID.randomUUID()).body())
                .get("reportId").asString();

        // Oldest first, filtered by status and target type.
        JsonNode queue = page(send("GET", "/api/admin/reports?status=OPEN&targetType=TRAVELER&size=100", admin, Role.ADMIN, null, null));
        java.util.List<String> ids = new java.util.ArrayList<>();
        queue.get("items").forEach(item -> {
            assertThat(item.get("status").asString()).isEqualTo("OPEN");
            assertThat(item.get("targetType").asString()).isEqualTo("TRAVELER");
            ids.add(item.get("reportId").asString());
        });
        assertThat(ids.indexOf(first)).isNotNegative().isLessThan(ids.indexOf(second));

        assertCode(moderate(admin, first, "RESOLVED", false, "no"), 400, "VALIDATION_ERROR");
        assertCode(moderate(admin, first, "OPEN", false, "Reopen please"), 400, "VALIDATION_ERROR");
        JsonNode review = JSON.readTree(moderate(admin, first, "IN_REVIEW", true, "Looking into it").body());
        assertThat(review.get("status").asString()).isEqualTo("IN_REVIEW");
        assertThat(review.get("assigneeId").asString()).isEqualTo(admin.toString());
        // Still open: a second report on the same target by the same traveler is a duplicate.
        assertCode(report(traveler, "TRAVELER", reported, UUID.randomUUID()), 409, "DUPLICATE_REPORT");

        JsonNode resolved = JSON.readTree(moderate(admin, first, "RESOLVED", false, "Confirmed by the manager").body());
        assertThat(resolved.get("status").asString()).isEqualTo("RESOLVED");
        assertThat(resolved.get("history")).hasSize(2);
        assertThat(resolved.get("history").get(1).get("note").asString()).isEqualTo("Confirmed by the manager");
        assertCode(moderate(admin, first, "DISMISSED", false, "Changed my mind"), 409, "REPORT_CLOSED");
        assertCode(moderate(admin, UUID.randomUUID().toString(), "DISMISSED", false, "Unknown"), 404, "NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type = 'ReportModerated' AND aggregate_id = ?",
                Long.class, UUID.fromString(first))).isEqualTo(2);

        // Closed, so the traveler may report again; the upheld report shows up as a count only.
        assertThat(report(traveler, "TRAVELER", reported, UUID.randomUUID()).statusCode()).isEqualTo(201);
        JsonNode subscribers = page(send("GET", "/api/managed-travels/" + travel + "/subscribers", manager,
                Role.TRAVEL_MANAGER, null, null));
        assertThat(subscribers.get("items").get(0).get("reportCount").asLong()).isOne();
        assertThat(subscribers.toString()).doesNotContain("Took the money");
    }

    // ---- XSS and SQL injection -------------------------------------------------------------

    @Test void scriptAndSqlPayloadsAreStoredAndReturnedAsInertText() throws Exception {
        UUID travel = travel(manager);
        Fixtures.subscription(jdbc, travel, traveler, manager, "COMPLETED", LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 5), "UTC");
        HttpResponse<String> created = feedback(traveler, travel, UUID.randomUUID(), 3, SCRIPT + " " + SQL);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(created.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("application/json"));
        assertThat(created.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");

        JsonNode listed = page(send("GET", "/api/feedback?travelId=" + travel, manager, Role.TRAVEL_MANAGER, null, null));
        assertThat(listed.get("items").get(0).get("comment").asString()).isEqualTo(SCRIPT + " " + SQL);
        // The SQL-looking text was a value, not a statement: the table is intact.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM feedback WHERE comment = ?", Long.class, SCRIPT + " " + SQL)).isOne();

        UUID other = UUID.randomUUID();
        HttpResponse<String> filed = send("POST", "/api/reports", traveler, Role.TRAVELER, UUID.randomUUID(),
                JSON.writeValueAsString(Map.of("targetType", "TRAVELER", "targetId", other.toString(),
                        "category", "OTHER", "description", SQL + " " + SCRIPT)));
        assertThat(filed.statusCode()).as(filed.body()).isEqualTo(201);
        assertThat(JSON.readTree(filed.body()).get("description").asString()).isEqualTo(SQL + " " + SCRIPT);

        // Injection through query parameters is rejected before any SQL runs.
        String injected = URLEncoder.encode("OPEN' OR '1'='1", StandardCharsets.UTF_8);
        assertCode(send("GET", "/api/admin/reports?status=" + injected, UUID.randomUUID(), Role.ADMIN, null, null),
                400, "VALIDATION_ERROR");
        assertCode(send("GET", "/api/feedback?managerId=" + injected, UUID.randomUUID(), Role.ADMIN, null, null),
                400, "VALIDATION_ERROR");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM report", Long.class)).isPositive();
    }

    // ---- Helpers -----------------------------------------------------------------------------

    private UUID travel(UUID managerId) {
        UUID id = UUID.randomUUID();
        TRAVELS.put(id, managerId);
        return id;
    }

    private void rate(UUID user, UUID travel, int rating, String comment) throws Exception {
        Fixtures.subscription(jdbc, travel, user, TRAVELS.get(travel), "COMPLETED", LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 5), "UTC");
        assertThat(feedback(user, travel, UUID.randomUUID(), rating, comment).statusCode()).isEqualTo(201);
    }

    private String status(UUID subscriptionId) {
        return jdbc.queryForObject("SELECT status FROM subscription WHERE subscription_id = ?", String.class, subscriptionId);
    }

    private HttpResponse<String> feedback(UUID user, UUID travel, UUID key, int rating, String comment) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("travelId", travel.toString(), "rating", rating));
        if (comment != null) body.put("comment", comment);
        return send("POST", "/api/feedback", user, Role.TRAVELER, key, JSON.writeValueAsString(body));
    }

    private HttpResponse<String> report(UUID user, String type, UUID target, UUID key) throws Exception {
        String category = type.equals("TRAVEL_MANAGER") ? "FRAUD" : "HARASSMENT";
        Role role = MANAGERS.contains(user) ? Role.TRAVEL_MANAGER : Role.TRAVELER;
        return send("POST", "/api/reports", user, role, key, JSON.writeValueAsString(Map.of("targetType", type,
                "targetId", target.toString(), "category", category, "description", "Took the money and vanished")));
    }

    private HttpResponse<String> moderate(UUID admin, String reportId, String status, boolean assign, String note) throws Exception {
        return send("PATCH", "/api/admin/reports/" + reportId, admin, Role.ADMIN, null,
                JSON.writeValueAsString(Map.of("status", status, "assignToSelf", assign, "note", note)));
    }

    private static JsonNode page(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static void assertCode(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(JSON.readTree(response.body()).get("code").asString()).isEqualTo(code);
    }

    private HttpResponse<String> send(String method, String path, UUID user, Role role, UUID key, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + codec.issue(user, role, UUID.randomUUID()).value())
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) request.header("Content-Type", "application/json");
        if (key != null) request.header("Idempotency-Key", key.toString());
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static HttpServer dependencies() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/token-validation", exchange -> respond(exchange, 200, null));
            server.createContext("/internal/users/", exchange -> {
                UUID id = UUID.fromString(exchange.getRequestURI().getPath().split("/")[3]);
                if (UNKNOWN_USERS.contains(id)) { respond(exchange, 404, null); return; }
                respond(exchange, 200, "{\"userId\":\"" + id + "\",\"displayName\":\"User " + id.toString().substring(0, 8)
                        + "\",\"memberSince\":\"2025-01-02T10:00:00Z\",\"active\":true}");
            });
            server.createContext("/internal/managers/", exchange -> {
                UUID id = UUID.fromString(exchange.getRequestURI().getPath().split("/")[3]);
                if (!MANAGERS.contains(id)) { respond(exchange, 404, null); return; }
                respond(exchange, 200, "{\"managerId\":\"" + id + "\",\"displayName\":\"Manager " + id.toString().substring(0, 8)
                        + "\",\"memberSince\":\"2025-01-02T10:00:00Z\"}");
            });
            server.createContext("/internal/travels/", exchange -> {
                UUID id = UUID.fromString(exchange.getRequestURI().getPath().split("/")[3]);
                UUID managerId = TRAVELS.get(id);
                if (managerId == null) { respond(exchange, 404, "{\"code\":\"NOT_FOUND\"}"); return; }
                respond(exchange, 200, "{\"travelId\":\"" + id + "\",\"managerId\":\"" + managerId + "\",\"title\":\"Trip " + id
                        + "\",\"startDate\":\"2026-09-01\",\"endDate\":\"2026-09-05\",\"timeZone\":\"UTC\",\"price\":100,"
                        + "\"currency\":\"EUR\"}");
            });
            server.start();
            return server;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        try (exchange) {
            if (body == null) { exchange.sendResponseHeaders(status, -1); return; }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
