package com.travelplan.payments;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.travelplan.common.security.AccessTokenCodec;
import com.travelplan.common.security.Role;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Subscription payments end to end through the HTTP API. engagement-service, the Stripe and PayPal
 * sandboxes and auth-service are local stand-ins with the same contracts; the TEST provider is the
 * real built-in simulation. Every test checks the number of charges the provider actually created.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "security.internal-service-token=test-internal",
        "payments.test-provider.enabled=true",
        "payments.test-provider.webhook-secret=whsec_test_only",
        "payments.simulation.enabled=true",
        "payments.stripe.secret-key=sk_test_sim_testonly",
        "payments.paypal.client-id=sim_client_testonly",
        "payments.paypal.client-secret=sim_secret_testonly",
        "payments.reconciliation-initial-delay=PT24H"
})
@ExtendWith(OutputCaptureExtension.class)
class SubscriptionPaymentApiTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String STRIPE_METHOD = "pm_ownedvisa0001";
    private static final String PAYPAL_METHOD = "VAULT_OWNED_001";
    private static final Map<UUID, StubSubscription> SUBSCRIPTIONS = new ConcurrentHashMap<>();
    private static final List<String> OUTCOMES = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean ENGAGEMENT_DOWN = new AtomicBoolean();
    /** Stripe stand-in creates the PaymentIntent but the answer is "lost" (503), like a timeout after charging. */
    private static final AtomicBoolean STRIPE_LOSES_ANSWER = new AtomicBoolean();
    private static final Map<String, String> STRIPE_INTENTS = new ConcurrentHashMap<>();
    private static final Map<String, String> PAYPAL_ORDERS = new ConcurrentHashMap<>();
    private static final Map<String, String> PROVIDER_REFUNDS = new ConcurrentHashMap<>();
    private static final HttpServer STANDINS = standIns();

    @Autowired AccessTokenCodec codec;
    @Autowired PaymentStore methodStore;
    @Autowired SubscriptionPaymentService service;
    @Autowired TestPaymentProvider testProvider;
    @Value("${local.server.port}") int port;

    private final UUID traveler = UUID.randomUUID();
    private final UUID otherTraveler = UUID.randomUUID();

    @DynamicPropertySource
    static void standInUrls(DynamicPropertyRegistry registry) {
        String url = "http://127.0.0.1:" + STANDINS.getAddress().getPort();
        registry.add("auth.service-url", () -> url);
        registry.add("services.engagement.url", () -> url);
        registry.add("payments.simulation.base-url", () -> url);
    }

    @AfterAll static void stop() { STANDINS.stop(0); }

    @BeforeEach void reset() {
        ENGAGEMENT_DOWN.set(false);
        STRIPE_LOSES_ANSWER.set(false);
    }

    @Test void approvedPaymentConfirmsTheSubscriptionAndRetriesReturnTheSamePayment() throws Exception {
        UUID subscription = subscription(traveler, 45050, "EUR");
        UUID key = UUID.randomUUID();
        int charges = testProvider.chargesCreated();

        HttpResponse<String> created = pay(traveler, key, subscription, null, "APPROVE");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode payment = JSON.readTree(created.body());
        String paymentId = payment.get("paymentId").asString();
        assertThat(payment.get("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(payment.get("amountMinor").asLong()).isEqualTo(45050);
        assertThat(payment.get("currency").asString()).isEqualTo("EUR");
        assertThat(payment.get("provider").asString()).isEqualTo("TEST");
        assertThat(payment.get("message").asString()).isEqualTo("Payment received. Your place is confirmed.");
        assertThat(OUTCOMES).containsOnlyOnce(subscription + ":SUCCEEDED:" + paymentId);
        assertThat(SUBSCRIPTIONS.get(subscription).status).isEqualTo("CONFIRMED");

        // Browser or gateway retry: same key, same body -> the same payment, no new charge.
        HttpResponse<String> retried = pay(traveler, key, subscription, null, "APPROVE");
        assertThat(retried.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(retried.body()).get("paymentId").asString()).isEqualTo(paymentId);
        // Duplicate request with another key (second tab): still one payment per subscription.
        assertCode(pay(traveler, UUID.randomUUID(), subscription, null, "APPROVE"), 409, "PAYMENT_ALREADY_EXISTS");
        // Same key for a different request.
        assertCode(pay(traveler, key, subscription, null, "DECLINE"), 409, "IDEMPOTENCY_KEY_REUSED");
        assertThat(testProvider.chargesCreated() - charges).isOne();
        assertThat(OUTCOMES.stream().filter(o -> o.startsWith(subscription.toString()))).hasSize(1);

        assertThat(JSON.readTree(send("GET", "/api/subscription-payments", traveler, Role.TRAVELER, null, null).body())
                .findValuesAsString("paymentId")).contains(paymentId);
        assertThat(send("GET", "/api/subscription-payments/" + paymentId, traveler, Role.TRAVELER, null, null).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/subscription-payments/" + paymentId, otherTraveler, Role.TRAVELER, null, null).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/api/subscription-payments/" + paymentId, UUID.randomUUID(), Role.ADMIN, null, null).statusCode()).isEqualTo(200);
    }

    @Test void concurrentDuplicateRequestsProduceExactlyOnePayment() throws Exception {
        UUID subscription = subscription(traveler, 10000, "EUR");
        UUID sharedKey = UUID.randomUUID();
        int charges = testProvider.chargesCreated();

        List<HttpResponse<String>> responses = concurrently(12, index ->
                pay(traveler, index < 8 ? sharedKey : UUID.randomUUID(), subscription, null, "APPROVE"));

        List<String> paymentIds = new ArrayList<>();
        for (HttpResponse<String> response : responses) {
            assertThat(response.statusCode()).as(response.body()).isIn(200, 201, 409);
            if (response.statusCode() != 409) paymentIds.add(JSON.readTree(response.body()).get("paymentId").asString());
            else assertThat(JSON.readTree(response.body()).get("code").asString()).isEqualTo("PAYMENT_ALREADY_EXISTS");
        }
        assertThat(paymentIds).isNotEmpty().allMatch(paymentIds.getFirst()::equals);
        assertThat(responses.stream().filter(r -> r.statusCode() == 201)).hasSize(1);
        assertThat(testProvider.chargesCreated() - charges).isOne();
        assertThat(OUTCOMES.stream().filter(o -> o.startsWith(subscription.toString()))).hasSize(1);
    }

    @Test void declinedPaymentFailsTerminallyAndReleasesThePlace() throws Exception {
        UUID subscription = subscription(traveler, 2500, "EUR");

        JsonNode payment = JSON.readTree(pay(traveler, UUID.randomUUID(), subscription, null, "DECLINE").body());

        assertThat(payment.get("state").asString()).isEqualTo("FAILED");
        assertThat(payment.get("failureCode").asString()).isEqualTo("PAYMENT_DECLINED");
        assertThat(payment.get("message").asString()).contains("declined").contains("place was released")
                .doesNotContain("Exception");
        assertThat(OUTCOMES).contains(subscription + ":FAILED:" + payment.get("paymentId").asString());
        assertThat(SUBSCRIPTIONS.get(subscription).status).isEqualTo("PAYMENT_FAILED");
    }

    @Test void providerTimeoutIsReconciledWithoutASecondCharge() throws Exception {
        UUID subscription = subscription(traveler, 7700, "EUR");
        UUID key = UUID.randomUUID();
        int charges = testProvider.chargesCreated();

        JsonNode payment = JSON.readTree(pay(traveler, key, subscription, null, "TIMEOUT").body());
        String paymentId = payment.get("paymentId").asString();
        assertThat(payment.get("state").asString()).isEqualTo("REQUIRES_RECONCILIATION");
        assertThat(payment.get("message").asString()).contains("will not be charged twice");
        assertThat(OUTCOMES.stream().filter(o -> o.startsWith(subscription.toString()))).isEmpty();
        assertThat(SUBSCRIPTIONS.get(subscription).status).isEqualTo("PENDING_PAYMENT");

        // The traveler retries: the recorded payment is returned, the provider is not called again.
        assertThat(JSON.readTree(pay(traveler, key, subscription, null, "TIMEOUT").body()).get("state").asString())
                .isEqualTo("REQUIRES_RECONCILIATION");

        service.reconcile(Instant.now().plusSeconds(1));

        JsonNode reconciled = JSON.readTree(send("GET", "/api/subscription-payments/" + paymentId, traveler, Role.TRAVELER, null, null).body());
        assertThat(reconciled.get("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(testProvider.chargesCreated() - charges).isOne();
        assertThat(OUTCOMES).containsOnlyOnce(subscription + ":SUCCEEDED:" + paymentId);
        service.reconcile(Instant.now().plusSeconds(1));
        assertThat(OUTCOMES).containsOnlyOnce(subscription + ":SUCCEEDED:" + paymentId);
    }

    @Test void anotherUsersMethodOrSubscriptionIsNotFoundAndNothingIsCharged() throws Exception {
        UUID mine = subscription(traveler, 5000, "EUR");
        UUID othersMethod = savedMethod(otherTraveler, "STRIPE", "pm_otheruser00001");
        int intents = STRIPE_INTENTS.size();

        HttpResponse<String> foreignMethod = pay(traveler, UUID.randomUUID(), mine, othersMethod, null);
        HttpResponse<String> unknownMethod = pay(traveler, UUID.randomUUID(), mine, UUID.randomUUID(), null);
        assertCode(foreignMethod, 404, "NOT_FOUND");
        // Indistinguishable from a method that does not exist: nothing about the other user leaks.
        assertThat(JSON.readTree(foreignMethod.body()).get("message")).isEqualTo(JSON.readTree(unknownMethod.body()).get("message"));
        assertThat(foreignMethod.body()).doesNotContain("pm_otheruser00001").doesNotContain(otherTraveler.toString());

        UUID theirs = subscription(otherTraveler, 5000, "EUR");
        assertCode(pay(traveler, UUID.randomUUID(), theirs, null, "APPROVE"), 404, "NOT_FOUND");
        assertThat(service.mine(traveler).stream().filter(p -> p.subscriptionId().equals(mine) || p.subscriptionId().equals(theirs))).isEmpty();
        assertThat(STRIPE_INTENTS).hasSize(intents);
    }

    @Test void savedStripeMethodPaysAndIsRefundedWhenThePaidSubscriptionIsCancelled(CapturedOutput output) throws Exception {
        UUID subscription = subscription(traveler, 45050, "EUR");
        UUID method = savedMethod(traveler, "STRIPE", STRIPE_METHOD);
        UUID key = UUID.randomUUID();

        HttpResponse<String> paid = pay(traveler, key, subscription, method, null);
        JsonNode payment = JSON.readTree(paid.body());
        assertThat(paid.statusCode()).as(paid.body()).isEqualTo(201);
        assertThat(payment.get("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(payment.get("methodDisplay").asString()).isEqualTo("visa •••• 4242");
        assertThat(pay(traveler, key, subscription, method, null).statusCode()).isEqualTo(200);
        String paymentId = payment.get("paymentId").asString();
        assertThat(STRIPE_INTENTS.keySet().stream().filter(k -> k.endsWith(paymentId))).hasSize(1);

        // engagement-service cancels the paid subscription and asks for the refund (twice, as a retry would).
        HttpResponse<String> refund = internalRefund(subscription, "test-internal");
        assertThat(refund.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(refund.body()).get("state").asString()).isEqualTo("REFUNDED");
        assertThat(JSON.readTree(internalRefund(subscription, "test-internal").body()).get("state").asString()).isEqualTo("REFUNDED");
        assertThat(PROVIDER_REFUNDS.keySet().stream().filter(k -> k.endsWith(paymentId))).hasSize(1);
        assertThat(internalRefund(subscription, "wrong").statusCode()).isEqualTo(403);
        assertThat(internalRefund(UUID.randomUUID(), "test-internal").statusCode()).isEqualTo(204);

        // Redacted logs: payment IDs and states only; no method reference, customer or secret.
        assertThat(output.getOut()).contains(paymentId).doesNotContain(STRIPE_METHOD)
                .doesNotContain("cus_owned").doesNotContain("sk_test_sim_testonly");
    }

    @Test void stripeAnswerLostAfterChargingIsReconciledToExactlyOnePaymentIntent() throws Exception {
        UUID subscription = subscription(traveler, 20000, "EUR");
        UUID method = savedMethod(traveler, "STRIPE", "pm_timeoutvisa001");
        STRIPE_LOSES_ANSWER.set(true);

        JsonNode payment = JSON.readTree(pay(traveler, UUID.randomUUID(), subscription, method, null).body());
        String paymentId = payment.get("paymentId").asString();
        assertThat(payment.get("state").asString()).isEqualTo("REQUIRES_RECONCILIATION");
        assertThat(SUBSCRIPTIONS.get(subscription).status).isEqualTo("PENDING_PAYMENT");
        assertThat(STRIPE_INTENTS.keySet().stream().filter(k -> k.endsWith(paymentId))).hasSize(1);

        service.reconcile(Instant.now().plusSeconds(1));

        assertThat(service.find(UUID.fromString(paymentId)).orElseThrow().state()).isEqualTo(SubscriptionPayment.State.SUCCEEDED);
        assertThat(STRIPE_INTENTS.keySet().stream().filter(k -> k.endsWith(paymentId))).hasSize(1);
        assertThat(SUBSCRIPTIONS.get(subscription).status).isEqualTo("CONFIRMED");
    }

    @Test void savedPayPalMethodPaysThroughTheOrdersApi() throws Exception {
        UUID subscription = subscription(traveler, 1200, "JPY");
        UUID method = savedMethod(traveler, "PAYPAL", PAYPAL_METHOD);

        JsonNode payment = JSON.readTree(pay(traveler, UUID.randomUUID(), subscription, method, null).body());

        assertThat(payment.get("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(payment.get("provider").asString()).isEqualTo("PAYPAL");
        assertThat(PAYPAL_ORDERS.keySet().stream().filter(k -> k.endsWith(payment.get("paymentId").asString()))).hasSize(1);
        assertThat(SUBSCRIPTIONS.get(subscription).status).isEqualTo("CONFIRMED");
    }

    @Test void onlyAVerifiedFreshWebhookCanResolveAPayment() throws Exception {
        UUID subscription = subscription(traveler, 3300, "EUR");
        JsonNode payment = JSON.readTree(pay(traveler, UUID.randomUUID(), subscription, null, "TIMEOUT").body());
        String paymentId = payment.get("paymentId").asString();
        String event = "{\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"test_ch_webhook01\","
                + "\"amount\":3300,\"currency\":\"eur\",\"metadata\":{\"payment_id\":\"" + paymentId + "\"}}}}";
        long now = Instant.now().getEpochSecond();

        assertCode(webhook("test", "t=" + now + ",v1=" + "0".repeat(64), event), 400, "INVALID_SIGNATURE");
        assertCode(webhook("test", null, event), 400, "INVALID_SIGNATURE");
        assertCode(webhook("test", signature("whsec_test_only", now - 600, event), event), 400, "INVALID_SIGNATURE");
        assertCode(webhook("test", signature("wrong-secret", now, event), event), 400, "INVALID_SIGNATURE");
        String tampered = event.replace("\"amount\":3300", "\"amount\":1");
        assertThat(webhook("test", signature("whsec_test_only", now, tampered), tampered).statusCode()).isEqualTo(200);
        assertThat(service.find(UUID.fromString(paymentId)).orElseThrow().state()).isEqualTo(SubscriptionPayment.State.REQUIRES_RECONCILIATION);
        // Stripe webhooks are not configured in this test, so that endpoint does not exist.
        assertThat(webhook("stripe", signature("whsec_test_only", now, event), event).statusCode()).isEqualTo(404);

        assertThat(webhook("test", signature("whsec_test_only", now, event), event).statusCode()).isEqualTo(200);
        assertThat(service.find(UUID.fromString(paymentId)).orElseThrow().state()).isEqualTo(SubscriptionPayment.State.SUCCEEDED);
        assertThat(OUTCOMES).containsOnlyOnce(subscription + ":SUCCEEDED:" + paymentId);
        // A redelivered event changes nothing.
        assertThat(webhook("test", signature("whsec_test_only", now, event), event).statusCode()).isEqualTo(200);
        assertThat(OUTCOMES).containsOnlyOnce(subscription + ":SUCCEEDED:" + paymentId);
    }

    @Test void successForAReservationThatExpiredMeanwhileIsRefunded() throws Exception {
        UUID subscription = subscription(traveler, 9900, "EUR");
        SUBSCRIPTIONS.get(subscription).refuseOutcome = true;

        JsonNode payment = JSON.readTree(pay(traveler, UUID.randomUUID(), subscription, null, "APPROVE").body());

        assertThat(payment.get("state").asString()).isEqualTo("REFUNDED");
        assertThat(payment.get("refundReason").asString()).isEqualTo("SUBSCRIPTION_NOT_PAYABLE");
        assertThat(payment.get("message").asString()).isEqualTo("This payment was refunded in full.");
    }

    @Test void outcomeIsDeliveredLaterWhenEngagementIsDown() throws Exception {
        UUID subscription = subscription(traveler, 1500, "EUR");
        ENGAGEMENT_DOWN.set(true);
        SUBSCRIPTIONS.get(subscription).termsDown = false;

        JsonNode payment = JSON.readTree(pay(traveler, UUID.randomUUID(), subscription, null, "APPROVE").body());
        assertThat(payment.get("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(payment.get("message").asString()).isEqualTo("Payment received. Confirming your place…");
        assertThat(SUBSCRIPTIONS.get(subscription).status).isEqualTo("PENDING_PAYMENT");

        ENGAGEMENT_DOWN.set(false);
        service.reconcile(Instant.now());
        assertThat(SUBSCRIPTIONS.get(subscription).status).isEqualTo("CONFIRMED");
        assertThat(service.find(UUID.fromString(payment.get("paymentId").asString())).orElseThrow().outcomeDelivered()).isTrue();
    }

    @Test void unpayableOrInvalidRequestsAreRefusedBeforeAnythingIsRecorded() throws Exception {
        UUID expired = subscription(traveler, 1000, "EUR");
        SUBSCRIPTIONS.get(expired).payable = false;
        assertCode(pay(traveler, UUID.randomUUID(), expired, null, "APPROVE"), 409, "SUBSCRIPTION_NOT_PAYABLE");
        assertThat(service.mine(traveler).stream().filter(p -> p.subscriptionId().equals(expired))).isEmpty();

        UUID subscription = subscription(traveler, 1000, "EUR");
        assertCode(send("POST", "/api/subscription-payments", traveler, Role.TRAVELER, null,
                "{\"subscriptionId\":\"" + subscription + "\",\"testOutcome\":\"APPROVE\"}"), 400, "VALIDATION_ERROR");
        assertCode(pay(traveler, UUID.randomUUID(), subscription, UUID.randomUUID(), "APPROVE"), 400, "VALIDATION_ERROR");
        assertCode(pay(traveler, UUID.randomUUID(), subscription, null, "MAYBE"), 400, "VALIDATION_ERROR");
        assertCode(pay(traveler, UUID.randomUUID(), UUID.randomUUID(), null, "APPROVE"), 404, "NOT_FOUND");

        SUBSCRIPTIONS.get(subscription).termsDown = true;
        HttpResponse<String> outage = pay(traveler, UUID.randomUUID(), subscription, null, "APPROVE");
        assertCode(outage, 503, "DEPENDENCY_UNAVAILABLE");
        assertThat(JSON.readTree(outage.body()).get("message").asString()).contains("Nothing was charged");
    }

    @Test void optionsListOnlyTheCallersActiveMethodsWithSafeDisplayData() throws Exception {
        UUID mine = savedMethod(traveler, "STRIPE", "pm_optionsvisa001");
        savedMethod(otherTraveler, "STRIPE", "pm_optionsother01");

        HttpResponse<String> response = send("GET", "/api/payment-options", traveler, Role.TRAVELER, null, null);

        JsonNode options = JSON.readTree(response.body());
        assertThat(options.get("testProviderEnabled").asBoolean()).isTrue();
        assertThat(options.get("methods").findValuesAsString("id")).containsExactly(mine.toString());
        assertThat(options.get("methods").get(0).get("available").asBoolean()).isTrue();
        assertThat(response.body()).doesNotContain("pm_options").doesNotContain("providerReference");
    }

    // --- helpers -----------------------------------------------------------------------------

    private UUID subscription(UUID owner, long amountMinor, String currency) {
        UUID id = UUID.randomUUID();
        SUBSCRIPTIONS.put(id, new StubSubscription(id, owner, amountMinor, currency));
        return id;
    }

    private UUID savedMethod(UUID owner, String provider, String reference) {
        UUID gateway = methodStore.createGateway(provider, provider + " sandbox").id();
        var claim = methodStore.beginConnect(owner, gateway, reference, "Travel card", UUID.randomUUID().toString(), "hash-" + reference);
        return methodStore.completeConnect(claim.method(), "visa •••• 4242").id();
    }

    private HttpResponse<String> pay(UUID user, UUID key, UUID subscription, UUID method, String testOutcome) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("subscriptionId", subscription.toString());
        if (method != null) body.put("paymentMethodId", method.toString());
        if (testOutcome != null) body.put("testOutcome", testOutcome);
        return send("POST", "/api/subscription-payments", user, Role.TRAVELER, key, JSON.writeValueAsString(body));
    }

    private HttpResponse<String> internalRefund(UUID subscription, String token) throws Exception {
        return raw(HttpRequest.newBuilder(URI.create(base() + "/internal/subscriptions/" + subscription + "/refund"))
                .header("X-Internal-Service-Token", token).POST(HttpRequest.BodyPublishers.noBody()));
    }

    private HttpResponse<String> webhook(String provider, String signature, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + "/api/payment-webhooks/" + provider))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (signature != null) request.header("stripe".equals(provider) ? "Stripe-Signature" : "Test-Signature", signature);
        return raw(request);
    }

    private static String signature(String secret, long timestamp, String body) {
        return "t=" + timestamp + ",v1=" + WebhookSignature.sign(secret, timestamp + "." + body);
    }

    private HttpResponse<String> send(String method, String path, UUID user, Role role, UUID key, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Authorization", "Bearer " + codec.issue(user, role, UUID.randomUUID()).value())
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) request.header("Content-Type", "application/json");
        if (key != null) request.header("Idempotency-Key", key.toString());
        return raw(request);
    }

    private static HttpResponse<String> raw(HttpRequest.Builder request) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private String base() { return "http://127.0.0.1:" + port; }

    private static void assertCode(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(JSON.readTree(response.body()).get("code").asString()).isEqualTo(code);
    }

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

    /** engagement-service's view of a subscription, as GET /internal/subscriptions/{id} returns it. */
    static final class StubSubscription {
        final UUID id;
        final UUID traveler;
        final long amountMinor;
        final String currency;
        volatile String status = "PENDING_PAYMENT";
        volatile boolean payable = true;
        volatile boolean refuseOutcome;
        volatile boolean termsDown;

        StubSubscription(UUID id, UUID traveler, long amountMinor, String currency) {
            this.id = id;
            this.traveler = traveler;
            this.amountMinor = amountMinor;
            this.currency = currency;
        }

        String json() {
            return "{\"subscriptionId\":\"" + id + "\",\"travelerId\":\"" + traveler + "\",\"status\":\"" + status
                    + "\",\"amountMinor\":" + amountMinor + ",\"currency\":\"" + currency
                    + "\",\"reservationExpiresAt\":\"" + Instant.now().plusSeconds(1200) + "\",\"payable\":" + payable + "}";
        }
    }

    private static HttpServer standIns() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newFixedThreadPool(16));
            server.createContext("/internal/token-validation", exchange -> respond(exchange, 200, null));
            server.createContext("/internal/subscriptions/", SubscriptionPaymentApiTest::engagement);
            server.createContext("/v1/payment_methods/", exchange -> {
                String reference = exchange.getRequestURI().getPath().substring("/v1/payment_methods/".length());
                if (!"Bearer sk_test_sim_testonly".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    respond(exchange, 401, "{}");
                    return;
                }
                respond(exchange, 200, "{\"id\":\"" + reference + "\",\"livemode\":false,\"customer\":\"cus_owned001\","
                        + "\"type\":\"card\",\"card\":{\"brand\":\"visa\",\"last4\":\"4242\"}}");
            });
            server.createContext("/v1/payment_intents", exchange -> {
                Map<String, String> form = form(exchange);
                String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                // Like Stripe: the same Idempotency-Key returns the first result instead of a new charge.
                String intent = STRIPE_INTENTS.computeIfAbsent(key, k -> "{\"id\":\"pi_" + UUID.randomUUID().toString().replace("-", "")
                        + "\",\"object\":\"payment_intent\",\"livemode\":false,\"status\":\"succeeded\",\"amount\":" + form.get("amount")
                        + ",\"currency\":\"" + form.get("currency") + "\",\"metadata\":{\"payment_id\":\""
                        + form.get("metadata[payment_id]") + "\"}}");
                if (STRIPE_LOSES_ANSWER.getAndSet(false)) { respond(exchange, 503, "{}"); return; }
                respond(exchange, 200, intent);
            });
            server.createContext("/v1/refunds", exchange -> {
                String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                respond(exchange, 200, PROVIDER_REFUNDS.computeIfAbsent(key, k -> "{\"id\":\"re_" + UUID.randomUUID().toString().replace("-", "")
                        + "\",\"status\":\"succeeded\"}"));
            });
            server.createContext("/v1/oauth2/token", exchange -> respond(exchange, 200, "{\"access_token\":\"sim_access_token\"}"));
            server.createContext("/v2/checkout/orders", exchange -> {
                JsonNode order = JSON.readTree(exchange.getRequestBody().readAllBytes());
                JsonNode unit = order.get("purchase_units").get(0);
                String key = exchange.getRequestHeaders().getFirst("PayPal-Request-Id");
                respond(exchange, 201, PAYPAL_ORDERS.computeIfAbsent(key, k -> "{\"id\":\"ORDER1\",\"status\":\"COMPLETED\","
                        + "\"purchase_units\":[{\"custom_id\":\"" + unit.get("custom_id").asString() + "\",\"payments\":{\"captures\":"
                        + "[{\"id\":\"CAPTURE" + UUID.randomUUID().toString().substring(0, 8) + "\",\"status\":\"COMPLETED\",\"amount\":"
                        + unit.get("amount").toString() + "}]}}]}"));
            });
            server.start();
            return server;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void engagement(HttpExchange exchange) {
        if (!"test-internal".equals(exchange.getRequestHeaders().getFirst("X-Internal-Service-Token"))) {
            respond(exchange, 403, null);
            return;
        }
        String[] parts = exchange.getRequestURI().getPath().split("/");
        StubSubscription subscription = SUBSCRIPTIONS.get(UUID.fromString(parts[3]));
        if (subscription == null) { respond(exchange, 404, "{\"code\":\"NOT_FOUND\"}"); return; }
        if ("GET".equals(exchange.getRequestMethod())) {
            if (subscription.termsDown) { respond(exchange, 503, null); return; }
            respond(exchange, 200, subscription.json());
            return;
        }
        if (ENGAGEMENT_DOWN.get()) { respond(exchange, 503, null); return; }
        try {
            JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
            String outcome = body.get("outcome").asString();
            synchronized (subscription) {
                if (subscription.refuseOutcome || !subscription.status.equals("PENDING_PAYMENT")) {
                    respond(exchange, 409, "{\"code\":\"SUBSCRIPTION_NOT_PENDING\"}");
                    return;
                }
                subscription.status = outcome.equals("SUCCEEDED") ? "CONFIRMED" : "PAYMENT_FAILED";
                OUTCOMES.add(subscription.id + ":" + outcome + ":" + body.get("paymentId").asString());
            }
            respond(exchange, 200, "{}");
        } catch (Exception ex) {
            respond(exchange, 400, null);
        }
    }

    private static Map<String, String> form(HttpExchange exchange) throws java.io.IOException {
        Map<String, String> fields = new HashMap<>();
        for (String pair : new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
            String[] parts = pair.split("=", 2);
            fields.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return fields;
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
