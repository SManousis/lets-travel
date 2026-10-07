package com.travelplan.engagement;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.travelplan.common.security.AccessTokenCodec;
import com.travelplan.common.security.Role;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Subscriptions through the HTTP API with a real (H2, PostgreSQL mode) database and Flyway
 * migrations. travel-service and user-service are stand-ins that keep the same contracts; the
 * stand-in's place counter is atomic like travel-service's Neo4j reservation.
 */
@ActiveProfiles("infra")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:engagement-subscriptions;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "management.server.port=0",
        "security.internal-service-token=test-internal",
        "subscriptions.housekeeping-initial-delay=PT24H"
})
class SubscriptionApiTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-07T09:00:00Z"));
    private static final Map<UUID, StubTravel> TRAVELS = new ConcurrentHashMap<>();
    private static final Set<UUID> INACTIVE_USERS = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger RELEASES = new AtomicInteger();
    private static final AtomicBoolean RELEASE_DOWN = new AtomicBoolean();
    private static final List<String> REFUND_REQUESTS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final AtomicBoolean REFUNDS_DOWN = new AtomicBoolean();
    private static final HttpServer DEPENDENCIES = dependencies();

    @Autowired AccessTokenCodec codec;
    @Autowired JdbcTemplate jdbc;
    @Autowired SubscriptionService service;
    @Autowired SubscriptionStore store;
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
        CLOCK.set(Instant.parse("2026-10-07T09:00:00Z"));
        RELEASE_DOWN.set(false);
        REFUNDS_DOWN.set(false);
    }

    @Test void subscribeReservesAPlaceSnapshotsTheTravelAndIsIdempotent() throws Exception {
        UUID travel = travel(manager, 5, LocalDate.of(2026, 11, 20), "Europe/Athens", "450.50", "EUR");
        UUID key = UUID.randomUUID();

        HttpResponse<String> created = subscribe(traveler, travel, key);
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode body = JSON.readTree(created.body());
        String id = body.get("subscriptionId").asString();
        assertThat(created.headers().firstValue("Location")).hasValue("/api/subscriptions/" + id);
        assertThat(body.get("status").asString()).isEqualTo("PENDING_PAYMENT");
        assertThat(body.get("travelTitle").asString()).isEqualTo("Trip " + travel);
        assertThat(body.get("managerId").asString()).isEqualTo(manager.toString());
        assertThat(body.get("travelerId").asString()).isEqualTo(traveler.toString());
        assertThat(body.get("price").get("amountMinor").asLong()).isEqualTo(45050);
        assertThat(body.get("price").get("currency").asString()).isEqualTo("EUR");
        assertThat(body.get("startDate").asString()).isEqualTo("2026-11-20");
        assertThat(body.get("cancellationCutoff").asString()).isEqualTo("2026-11-17");
        assertThat(body.get("timeZone").asString()).isEqualTo("Europe/Athens");
        assertThat(body.get("travelerMayCancel").asBoolean()).isTrue();
        assertThat(body.get("paymentId").isNull()).isTrue();
        assertThat(body.get("reservationExpiresAt").asString()).isEqualTo("2026-10-07T09:30:00Z");

        HttpResponse<String> retried = subscribe(traveler, travel, key);
        assertThat(retried.statusCode()).isEqualTo(201);
        assertThat(JSON.readTree(retried.body()).get("subscriptionId").asString()).isEqualTo(id);
        assertThat(TRAVELS.get(travel).holders).containsExactly(traveler);

        assertCode(subscribe(traveler, travel(manager, 5, LocalDate.of(2026, 11, 20), "UTC", "1", "EUR"), key),
                409, "IDEMPOTENCY_KEY_REUSED");
        assertCode(subscribe(traveler, travel, UUID.randomUUID()), 409, "ALREADY_SUBSCRIBED");
        assertCode(send("POST", "/api/subscriptions", traveler, Role.TRAVELER, null, "{\"travelId\":\"" + travel + "\"}"),
                400, "VALIDATION_ERROR");
        assertCode(send("POST", "/api/subscriptions", traveler, Role.TRAVELER, UUID.randomUUID(), "{}"), 400, "VALIDATION_ERROR");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = ?",
                Long.class, UUID.fromString(id), "SubscriptionReserved")).isOne();
        String payload = jdbc.queryForObject("SELECT payload FROM outbox_event WHERE aggregate_id = ?", String.class,
                UUID.fromString(id));
        assertThat(payload).contains("\"status\":\"PENDING_PAYMENT\"").doesNotContain("Traveler ", "@");

        HttpResponse<String> mine = send("GET", "/api/subscriptions", traveler, Role.TRAVELER, null, null);
        assertThat(JSON.readTree(mine.body()).get("totalElements").asLong()).isOne();
        assertThat(send("GET", "/api/subscriptions/" + id, traveler, Role.TRAVELER, null, null).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/subscriptions/" + id, UUID.randomUUID(), Role.TRAVELER, null, null).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/api/subscriptions/" + id, manager, Role.TRAVEL_MANAGER, null, null).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/subscriptions/" + id, UUID.randomUUID(), Role.TRAVEL_MANAGER, null, null).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/api/subscriptions/" + id, UUID.randomUUID(), Role.ADMIN, null, null).statusCode()).isEqualTo(200);
    }

    @Test void subscribeRejectsUnknownFullUnbookableTravelsAndInactiveTravelers() throws Exception {
        assertCode(subscribe(traveler, UUID.randomUUID(), UUID.randomUUID()), 404, "NOT_FOUND");
        UUID full = travel(manager, 1, LocalDate.of(2026, 12, 1), "UTC", "10", "EUR");
        assertThat(subscribe(UUID.randomUUID(), full, UUID.randomUUID()).statusCode()).isEqualTo(201);
        assertCode(subscribe(traveler, full, UUID.randomUUID()), 409, "TRAVEL_FULL");
        UUID closed = travel(manager, 3, LocalDate.of(2026, 12, 1), "UTC", "10", "EUR");
        TRAVELS.get(closed).bookable = false;
        assertCode(subscribe(traveler, closed, UUID.randomUUID()), 409, "TRAVEL_NOT_BOOKABLE");

        UUID disabled = UUID.randomUUID();
        INACTIVE_USERS.add(disabled);
        UUID open = travel(manager, 3, LocalDate.of(2026, 12, 1), "UTC", "10", "EUR");
        assertCode(subscribe(disabled, open, UUID.randomUUID()), 409, "TRAVELER_NOT_ACTIVE");
        assertThat(TRAVELS.get(open).holders).isEmpty();
    }

    @Test void concurrentSubscribersCannotOversellAndDuplicateRequestsHoldOnePlace() throws Exception {
        UUID travel = travel(manager, 2, LocalDate.of(2026, 12, 10), "UTC", "99", "EUR");
        List<HttpResponse<String>> results = concurrently(10, index -> subscribe(UUID.randomUUID(), travel, UUID.randomUUID()));
        assertThat(results).filteredOn(response -> response.statusCode() == 201).hasSize(2);
        assertThat(results).filteredOn(response -> response.statusCode() == 409
                && response.body().contains("TRAVEL_FULL")).hasSize(8);
        assertThat(activeCount(travel)).isEqualTo(2);

        UUID roomy = travel(manager, 10, LocalDate.of(2026, 12, 10), "UTC", "99", "EUR");
        List<HttpResponse<String>> duplicates = concurrently(6, index -> subscribe(traveler, roomy, UUID.randomUUID()));
        assertThat(duplicates).filteredOn(response -> response.statusCode() == 201).hasSize(1);
        assertThat(duplicates).filteredOn(response -> response.body().contains("ALREADY_SUBSCRIBED")).hasSize(5);
        assertThat(activeCount(roomy)).isOne();
        assertThat(TRAVELS.get(roomy).holders).containsExactly(traveler);

        UUID sameKey = UUID.randomUUID();
        UUID third = travel(manager, 10, LocalDate.of(2026, 12, 10), "UTC", "99", "EUR");
        UUID other = UUID.randomUUID();
        List<HttpResponse<String>> retries = concurrently(6, index -> subscribe(other, third, sameKey));
        assertThat(retries).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(201));
        assertThat(retries.stream().map(response -> JSON.readTree(response.body()).get("subscriptionId").asString())
                .distinct()).hasSize(1);
    }

    @Test void travelerCancellationFollowsTheThreeDayCutoffInTheTravelsTimeZone() throws Exception {
        ZoneId athens = ZoneId.of("Europe/Athens");
        UUID travel = travel(manager, 10, LocalDate.of(2026, 11, 20), "Europe/Athens", "100", "EUR");

        // Four days before: allowed, the place is given back, and a repeat returns the same result.
        String fourDays = subscriptionId(subscribe(traveler, travel, UUID.randomUUID()));
        at(LocalDateTime.of(2026, 11, 16, 10, 0), athens);
        int releasesBefore = RELEASES.get();
        HttpResponse<String> cancelled = cancel(traveler, fourDays);
        assertThat(cancelled.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(cancelled.body()).get("status").asString()).isEqualTo("CANCELLED_BY_TRAVELER");
        assertThat(TRAVELS.get(travel).holders).doesNotContain(traveler);
        assertThat(RELEASES.get()).isEqualTo(releasesBefore + 1);
        assertThat(cancel(traveler, fourDays).statusCode()).isEqualTo(200);
        assertThat(RELEASES.get()).isEqualTo(releasesBefore + 1);
        assertThat(cancel(UUID.randomUUID(), fourDays).statusCode()).isEqualTo(404);

        // Exactly three days before, last minute of the local day: allowed.
        String exact = subscriptionId(subscribe(traveler, travel, UUID.randomUUID()));
        at(LocalDateTime.of(2026, 11, 17, 23, 59), athens);
        assertThat(cancel(traveler, exact).statusCode()).isEqualTo(200);

        // First minute of the next local day (still the 17th in UTC): rejected.
        String late = subscriptionId(subscribe(traveler, travel(manager, 10, LocalDate.of(2026, 11, 20),
                "Europe/Athens", "100", "EUR"), UUID.randomUUID()));
        at(LocalDateTime.of(2026, 11, 18, 0, 0, 30), athens);
        assertThat(CLOCK.instant()).isEqualTo(Instant.parse("2026-11-17T22:00:30Z"));
        assertCode(cancel(traveler, late), 409, "CANCELLATION_CUTOFF_PASSED");
        assertThat(JSON.readTree(send("GET", "/api/subscriptions/" + late, traveler, Role.TRAVELER, null, null).body())
                .get("travelerMayCancel").asBoolean()).isFalse();

        // On the start date: rejected, and the subscription is untouched.
        at(LocalDateTime.of(2026, 11, 20, 9, 0), athens);
        assertCode(cancel(traveler, late), 409, "CANCELLATION_CUTOFF_PASSED");
        assertThat(status(late)).isEqualTo("PENDING_PAYMENT");

        // An earlier successful cancellation stays retry-safe after the cutoff.
        assertThat(cancel(traveler, exact).statusCode()).isEqualTo(200);
        assertCode(send("POST", "/api/subscriptions/" + exact + "/cancellation", traveler, Role.TRAVELER, null, null),
                400, "VALIDATION_ERROR");
    }

    @Test void travelLocalDateWinsOverUtcWestOfGreenwich() throws Exception {
        UUID travel = travel(manager, 10, LocalDate.of(2026, 11, 20), "America/Los_Angeles", "100", "USD");
        String id = subscriptionId(subscribe(traveler, travel, UUID.randomUUID()));
        CLOCK.set(Instant.parse("2026-11-18T05:00:00Z")); // 18th in UTC, 17th 21:00 in Los Angeles
        assertThat(cancel(traveler, id).statusCode()).isEqualTo(200);
    }

    @Test void managersSeeAndRemoveOnlyTheirOwnSubscribersWithAReason() throws Exception {
        UUID travel = travel(manager, 10, LocalDate.of(2026, 11, 20), "Europe/Athens", "100", "EUR");
        String id = subscriptionId(subscribe(traveler, travel, UUID.randomUUID()));
        String path = "/api/managed-travels/" + travel + "/subscribers";

        HttpResponse<String> list = send("GET", path, manager, Role.TRAVEL_MANAGER, null, null);
        assertThat(list.statusCode()).isEqualTo(200);
        JsonNode item = JSON.readTree(list.body()).get("items").get(0);
        assertThat(item.get("subscriptionId").asString()).isEqualTo(id);
        assertThat(item.get("displayName").asString()).isEqualTo("Traveler " + traveler.toString().substring(0, 8));
        assertThat(item.get("memberSince").asString()).isEqualTo("2025-01-02");
        assertThat(item.get("completedTrips").asLong()).isZero();
        assertThat(list.body()).doesNotContain("@example.com", "passport");
        assertThat(send("GET", path, UUID.randomUUID(), Role.TRAVEL_MANAGER, null, null).statusCode()).isEqualTo(404);
        assertThat(send("GET", path, traveler, Role.TRAVELER, null, null).statusCode()).isEqualTo(403);
        assertThat(send("GET", path, UUID.randomUUID(), Role.ADMIN, null, null).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/managed-travels/" + UUID.randomUUID() + "/subscribers", manager,
                Role.TRAVEL_MANAGER, null, null).statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(send("GET", path + "?status=CONFIRMED", manager, Role.TRAVEL_MANAGER, null, null).body())
                .get("totalElements").asLong()).isZero();

        String removal = path + "/" + id + "/removal";
        assertCode(remove(removal, UUID.randomUUID(), Role.TRAVEL_MANAGER, "Overbooked"), 404, "NOT_FOUND");
        assertCode(remove(removal, manager, Role.TRAVEL_MANAGER, "  "), 400, "VALIDATION_ERROR");
        assertCode(remove("/api/managed-travels/" + travel(manager, 3, LocalDate.of(2026, 12, 1), "UTC", "1", "EUR")
                + "/subscribers/" + id + "/removal", manager, Role.TRAVEL_MANAGER, "Wrong travel"), 404, "NOT_FOUND");

        // Past the traveler cutoff a manager can still remove, with a reason that is audited.
        at(LocalDateTime.of(2026, 11, 19, 8, 0), ZoneId.of("Europe/Athens"));
        HttpResponse<String> removed = remove(removal, manager, Role.TRAVEL_MANAGER, "Missing travel documents");
        assertThat(removed.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(removed.body()).get("status").asString()).isEqualTo("CANCELLED_BY_MANAGER");
        assertThat(JSON.readTree(removed.body()).get("cancellationReason").asString()).isEqualTo("Missing travel documents");
        assertThat(remove(removal, manager, Role.TRAVEL_MANAGER, "Missing travel documents").statusCode()).isEqualTo(200);
        assertThat(TRAVELS.get(travel).holders).isEmpty();
        assertThat(store.history(UUID.fromString(id))).extracting(SubscriptionStore.HistoryEntry::toStatus)
                .containsExactly("PENDING_PAYMENT", "CANCELLED_BY_MANAGER");
        assertThat(store.history(UUID.fromString(id)).get(1).reason()).isEqualTo("Missing travel documents");
        assertThat(store.history(UUID.fromString(id)).get(1).actorId()).isEqualTo(manager);
        assertCode(cancel(traveler, id), 409, "SUBSCRIPTION_NOT_ACTIVE");

        // Admins can remove from any manager's travel.
        UUID other = UUID.randomUUID();
        String second = subscriptionId(subscribe(other, travel, UUID.randomUUID()));
        assertThat(remove(path + "/" + second + "/removal", UUID.randomUUID(), Role.ADMIN, "Admin decision").statusCode())
                .isEqualTo(200);
    }

    @Test void paymentOutcomeConfirmsOrReleasesAndIsRetrySafe() throws Exception {
        UUID travel = travel(manager, 10, LocalDate.of(2026, 12, 20), "UTC", "100", "EUR");
        String confirmed = subscriptionId(subscribe(traveler, travel, UUID.randomUUID()));
        UUID payment = UUID.randomUUID();

        HttpResponse<String> ok = outcome(confirmed, "SUCCEEDED", payment, "test-internal");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(ok.body()).get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(outcome(confirmed, "SUCCEEDED", payment, "test-internal").statusCode()).isEqualTo(200);
        assertCode(outcome(confirmed, "SUCCEEDED", UUID.randomUUID(), "test-internal"), 409, "PAYMENT_MISMATCH");
        assertCode(outcome(confirmed, "FAILED", payment, "test-internal"), 409, "SUBSCRIPTION_NOT_PENDING");
        assertThat(outcome(confirmed, "SUCCEEDED", payment, "wrong").statusCode()).isEqualTo(403);
        assertThat(outcome(UUID.randomUUID().toString(), "SUCCEEDED", payment, "test-internal").statusCode()).isEqualTo(404);
        JsonNode view = JSON.readTree(send("GET", "/api/subscriptions/" + confirmed, traveler, Role.TRAVELER, null, null).body());
        assertThat(view.get("paymentId").asString()).isEqualTo(payment.toString());
        assertThat(view.get("reservationExpiresAt").isNull()).isTrue();

        UUID other = UUID.randomUUID();
        String failed = subscriptionId(subscribe(other, travel, UUID.randomUUID()));
        assertThat(TRAVELS.get(travel).holders).contains(other);
        assertThat(JSON.readTree(outcome(failed, "FAILED", UUID.randomUUID(), "test-internal").body())
                .get("status").asString()).isEqualTo("PAYMENT_FAILED");
        assertThat(TRAVELS.get(travel).holders).doesNotContain(other);
        assertThat(outcome(failed, "FAILED", UUID.randomUUID(), "test-internal").statusCode()).isEqualTo(200);
        // The traveler can try again with a new subscription.
        assertThat(subscribe(other, travel, UUID.randomUUID()).statusCode()).isEqualTo(201);
    }

    @Test void unpaidReservationsExpireAfterThirtyMinutesAndReleaseThePlace() throws Exception {
        UUID travel = travel(manager, 10, LocalDate.of(2026, 12, 20), "UTC", "120.00", "EUR");
        String id = subscriptionId(subscribe(traveler, travel, UUID.randomUUID()));

        JsonNode terms = JSON.readTree(internalGet(id, "test-internal").body());
        assertThat(terms.get("travelerId").asString()).isEqualTo(traveler.toString());
        assertThat(terms.get("amountMinor").asLong()).isEqualTo(12000);
        assertThat(terms.get("currency").asString()).isEqualTo("EUR");
        assertThat(terms.get("payable").asBoolean()).isTrue();
        assertThat(internalGet(id, "wrong").statusCode()).isEqualTo(403);
        assertThat(internalGet(UUID.randomUUID().toString(), "test-internal").statusCode()).isEqualTo(404);

        CLOCK.set(Instant.parse("2026-10-07T09:29:59Z"));
        service.expireUnpaidReservations();
        assertThat(status(id)).isEqualTo("PENDING_PAYMENT");

        CLOCK.set(Instant.parse("2026-10-07T09:30:01Z"));
        assertThat(JSON.readTree(internalGet(id, "test-internal").body()).get("payable").asBoolean()).isFalse();
        assertThat(service.expireUnpaidReservations()).isOne();
        assertThat(status(id)).isEqualTo("PAYMENT_FAILED");
        assertThat(TRAVELS.get(travel).holders).doesNotContain(traveler);
        assertThat(jdbc.queryForObject("SELECT reason FROM subscription_history WHERE subscription_id = ? AND to_status = ?",
                String.class, UUID.fromString(id), "PAYMENT_FAILED")).isEqualTo("RESERVATION_EXPIRED");
        assertThat(service.expireUnpaidReservations()).isZero();

        // A payment that still succeeds now is refused; payment-service refunds it (docs/payments.md).
        assertCode(outcome(id, "SUCCEEDED", UUID.randomUUID(), "test-internal"), 409, "SUBSCRIPTION_NOT_PENDING");
        assertThat(subscribe(traveler, travel, UUID.randomUUID()).statusCode()).isEqualTo(201);
    }

    @Test void cancellingAPaidSubscriptionRequestsARefundUntilPaymentServiceAcceptsIt() throws Exception {
        UUID travel = travel(manager, 10, LocalDate.of(2026, 12, 20), "UTC", "100", "EUR");
        String paid = subscriptionId(subscribe(traveler, travel, UUID.randomUUID()));
        outcome(paid, "SUCCEEDED", UUID.randomUUID(), "test-internal");
        REFUNDS_DOWN.set(true);

        assertThat(JSON.readTree(cancel(traveler, paid).body()).get("status").asString()).isEqualTo("CANCELLED_BY_TRAVELER");
        assertThat(refundDue(paid)).isTrue();
        assertThat(REFUND_REQUESTS).doesNotContain(paid);

        REFUNDS_DOWN.set(false);
        service.retryDueRefunds();
        assertThat(refundDue(paid)).isFalse();
        assertThat(REFUND_REQUESTS).containsOnlyOnce(paid);
        service.retryDueRefunds();
        assertThat(REFUND_REQUESTS).containsOnlyOnce(paid);

        // Manager removal of a paid subscriber refunds straight away; an unpaid cancellation owes nothing.
        UUID other = UUID.randomUUID();
        String removed = subscriptionId(subscribe(other, travel, UUID.randomUUID()));
        outcome(removed, "SUCCEEDED", UUID.randomUUID(), "test-internal");
        assertThat(remove("/api/managed-travels/" + travel + "/subscribers/" + removed + "/removal", manager,
                Role.TRAVEL_MANAGER, "Trip rescheduled").statusCode()).isEqualTo(200);
        assertThat(REFUND_REQUESTS).containsOnlyOnce(removed);
        String unpaid = subscriptionId(subscribe(UUID.randomUUID(), travel, UUID.randomUUID()));
        String unpaidOwner = jdbc.queryForObject("SELECT traveler_id FROM subscription WHERE subscription_id = ?", String.class,
                UUID.fromString(unpaid));
        cancel(UUID.fromString(unpaidOwner), unpaid);
        assertThat(REFUND_REQUESTS).doesNotContain(unpaid);
        assertThat(refundDue(unpaid)).isFalse();
    }

    @Test void placeReleaseIsFinishedLaterWhenTravelServiceIsDown() throws Exception {
        UUID travel = travel(manager, 10, LocalDate.of(2026, 12, 20), "UTC", "100", "EUR");
        String id = subscriptionId(subscribe(traveler, travel, UUID.randomUUID()));
        RELEASE_DOWN.set(true);
        assertThat(JSON.readTree(cancel(traveler, id).body()).get("status").asString()).isEqualTo("CANCELLED_BY_TRAVELER");
        assertThat(TRAVELS.get(travel).holders).contains(traveler);
        RELEASE_DOWN.set(false);
        service.retryUnreleasedPlaces();
        assertThat(TRAVELS.get(travel).holders).doesNotContain(traveler);
        assertThat(jdbc.queryForObject("SELECT seat_released FROM subscription WHERE subscription_id = ?", Boolean.class,
                UUID.fromString(id))).isTrue();
    }

    @Test void confirmedSubscriptionsCompleteAfterTheTravelEndsAndCountAsCompletedTrips() throws Exception {
        UUID travel = travel(manager, 10, LocalDate.of(2026, 10, 20), "Asia/Tokyo", "100", "EUR");
        String id = subscriptionId(subscribe(traveler, travel, UUID.randomUUID()));
        outcome(id, "SUCCEEDED", UUID.randomUUID(), "test-internal");
        LocalDate end = TRAVELS.get(travel).start.plusDays(2);
        at(end.atTime(23, 0), ZoneId.of("Asia/Tokyo"));
        service.completeFinishedTravels();
        assertThat(status(id)).isEqualTo("CONFIRMED");
        at(end.plusDays(1).atTime(0, 5), ZoneId.of("Asia/Tokyo"));
        service.completeFinishedTravels();
        assertThat(status(id)).isEqualTo("COMPLETED");
        JsonNode item = JSON.readTree(send("GET", "/api/managed-travels/" + travel + "/subscribers", manager,
                Role.TRAVEL_MANAGER, null, null).body()).get("items").get(0);
        assertThat(item.get("completedTrips").asLong()).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = ?",
                Long.class, UUID.fromString(id), "SubscriptionCompleted")).isOne();
    }

    // --- helpers -----------------------------------------------------------------------------

    private interface Call { HttpResponse<String> run(int index) throws Exception; }

    private static List<HttpResponse<String>> concurrently(int count, Call call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> { start.await(); return call.run(index); }));
            }
            start.countDown();
            List<HttpResponse<String>> results = new ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) results.add(future.get());
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private long activeCount(UUID travel) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM subscription WHERE travel_id = ? AND status IN ('PENDING_PAYMENT','CONFIRMED')",
                Long.class, travel);
    }

    private String status(String id) {
        return jdbc.queryForObject("SELECT status FROM subscription WHERE subscription_id = ?", String.class, UUID.fromString(id));
    }

    private static void at(LocalDateTime local, ZoneId zone) { CLOCK.set(local.atZone(zone).toInstant()); }

    private static UUID travel(UUID manager, int capacity, LocalDate start, String zone, String price, String currency) {
        UUID id = UUID.randomUUID();
        TRAVELS.put(id, new StubTravel(id, manager, capacity, start, zone, new BigDecimal(price), currency));
        return id;
    }

    private HttpResponse<String> subscribe(UUID user, UUID travel, UUID key) throws Exception {
        return send("POST", "/api/subscriptions", user, Role.TRAVELER, key, "{\"travelId\":\"" + travel + "\"}");
    }

    private HttpResponse<String> cancel(UUID user, String id) throws Exception {
        return send("POST", "/api/subscriptions/" + id + "/cancellation", user, Role.TRAVELER, UUID.randomUUID(), null);
    }

    private HttpResponse<String> remove(String path, UUID user, Role role, String reason) throws Exception {
        return send("POST", path, user, role, UUID.randomUUID(), "{\"reason\":\"" + reason + "\"}");
    }

    private HttpResponse<String> outcome(String id, String outcome, UUID payment, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/subscriptions/" + id
                        + "/payment-outcome")).timeout(Duration.ofSeconds(30)).header("X-Internal-Service-Token", token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"outcome\":\"" + outcome + "\",\"paymentId\":\"" + payment + "\"}"))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private HttpResponse<String> internalGet(String id, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/subscriptions/" + id))
                .timeout(Duration.ofSeconds(30)).header("X-Internal-Service-Token", token).GET().build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private boolean refundDue(String id) {
        return jdbc.queryForObject("SELECT refund_due FROM subscription WHERE subscription_id = ?", Boolean.class, UUID.fromString(id));
    }

    private static String subscriptionId(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return JSON.readTree(response.body()).get("subscriptionId").asString();
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

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) { this.now = now; }

        void set(Instant instant) { now = instant; }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(ZoneId zone) { return this; }

        @Override public Instant instant() { return now; }
    }

    static final class StubTravel {
        final UUID id;
        final UUID manager;
        final int capacity;
        final LocalDate start;
        final String zone;
        final BigDecimal price;
        final String currency;
        final Set<UUID> holders = ConcurrentHashMap.newKeySet();
        volatile boolean bookable = true;

        StubTravel(UUID id, UUID manager, int capacity, LocalDate start, String zone, BigDecimal price, String currency) {
            this.id = id;
            this.manager = manager;
            this.capacity = capacity;
            this.start = start;
            this.zone = zone;
            this.price = price;
            this.currency = currency;
        }

        String json() {
            return "{\"travelId\":\"" + id + "\",\"managerId\":\"" + manager + "\",\"title\":\"Trip " + id
                    + "\",\"status\":\"PUBLISHED\",\"startDate\":\"" + start + "\",\"endDate\":\"" + start.plusDays(2)
                    + "\",\"timeZone\":\"" + zone + "\",\"price\":" + price + ",\"currency\":\"" + currency
                    + "\",\"capacity\":" + capacity + ",\"remainingCapacity\":" + (capacity - holders.size())
                    + ",\"bookable\":" + bookable + "}";
        }
    }

    private static HttpServer dependencies() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newFixedThreadPool(16));
            server.createContext("/internal/token-validation", exchange -> respond(exchange, 200, null));
            server.createContext("/internal/users/", exchange -> {
                String[] parts = exchange.getRequestURI().getPath().split("/");
                UUID id = UUID.fromString(parts[3]);
                respond(exchange, 200, "{\"userId\":\"" + id + "\",\"displayName\":\"Traveler " + id.toString().substring(0, 8)
                        + "\",\"memberSince\":\"2025-01-02T10:00:00Z\",\"active\":" + !INACTIVE_USERS.contains(id)
                        + ",\"email\":\"private@example.com\"}");
            });
            server.createContext("/internal/travels/", SubscriptionApiTest::travelEndpoint);
            // payment-service's refund hand-off.
            server.createContext("/internal/subscriptions/", exchange -> {
                if (REFUNDS_DOWN.get()) { respond(exchange, 503, null); return; }
                REFUND_REQUESTS.add(exchange.getRequestURI().getPath().split("/")[3]);
                respond(exchange, 200, "{\"state\":\"REFUNDED\"}");
            });
            server.start();
            return server;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void travelEndpoint(HttpExchange exchange) {
        if (!"test-internal".equals(exchange.getRequestHeaders().getFirst("X-Internal-Service-Token"))) {
            respond(exchange, 403, null);
            return;
        }
        String[] parts = exchange.getRequestURI().getPath().split("/");
        StubTravel travel = TRAVELS.get(UUID.fromString(parts[3]));
        String method = exchange.getRequestMethod();
        if (method.equals("DELETE")) {
            if (RELEASE_DOWN.get()) { respond(exchange, 503, null); return; }
            RELEASES.incrementAndGet();
            if (travel != null) travel.holders.remove(UUID.fromString(parts[5]));
            respond(exchange, 204, null);
            return;
        }
        if (travel == null) { respond(exchange, 404, "{\"code\":\"NOT_FOUND\"}"); return; }
        if (method.equals("GET")) { respond(exchange, 200, travel.json()); return; }
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            UUID traveler = UUID.fromString(JSON.readTree(body).get("travelerId").asString());
            synchronized (travel) {
                if (!travel.holders.contains(traveler)) {
                    if (!travel.bookable) { respond(exchange, 409, "{\"code\":\"TRAVEL_NOT_BOOKABLE\"}"); return; }
                    if (travel.holders.size() >= travel.capacity) { respond(exchange, 409, "{\"code\":\"TRAVEL_FULL\"}"); return; }
                    travel.holders.add(traveler);
                }
            }
            respond(exchange, 200, travel.json());
        } catch (Exception ex) {
            respond(exchange, 400, null);
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
