package com.travelplan.payments;

import com.travelplan.payments.ChargeProvider.ChargeRequest;
import com.travelplan.payments.ChargeProvider.ChargeResult;
import com.travelplan.payments.ChargeProvider.RefundRequest;
import com.travelplan.payments.SubscriptionPayment.State;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pays for subscriptions. The rules, in order:
 * <ol>
 *   <li>Idempotency: the same (user, Idempotency-Key) returns the first payment; a subscription can
 *       have only one payment whatever key is used. Both are unique constraints in the database.</li>
 *   <li>Ownership: the subscription and the saved method must belong to the caller, else 404.</li>
 *   <li>The amount comes from engagement-service's price snapshot, never from the request.</li>
 *   <li>The payment row is saved as PROCESSING before the provider is called.</li>
 *   <li>A lost/ambiguous provider answer becomes REQUIRES_RECONCILIATION; reconciliation replays
 *       the same provider idempotency key, so the provider returns the first result instead of
 *       charging again.</li>
 *   <li>engagement-service confirms the subscription only after a verified success; a decline
 *       makes it PAYMENT_FAILED and releases the place. If it refuses a success (the reservation
 *       expired or was cancelled meanwhile) the payment is refunded in full.</li>
 * </ol>
 * Logs carry payment IDs and states only: no provider references, card data or secrets.
 */
@Service
class SubscriptionPaymentService {
    private static final Logger log = LoggerFactory.getLogger(SubscriptionPaymentService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final int MAX_ATTEMPTS = 10;
    static final Duration STALE_PROCESSING = Duration.ofMinutes(2);
    static final List<String> TEST_OUTCOMES = List.of("APPROVE", "DECLINE", "TIMEOUT");

    private final SubscriptionPaymentStore payments;
    private final PaymentStore methods;
    private final SubscriptionClient subscriptions;
    private final Map<String, ChargeProvider> providers = new HashMap<>();
    private final String stripeWebhookSecret;
    private final String testWebhookSecret;

    SubscriptionPaymentService(SubscriptionPaymentStore payments, PaymentStore methods, SubscriptionClient subscriptions,
                               List<ChargeProvider> providers,
                               @Value("${payments.stripe.webhook-secret:}") String stripeWebhookSecret,
                               @Value("${payments.test-provider.webhook-secret:}") String testWebhookSecret) {
        this.payments = payments;
        this.methods = methods;
        this.subscriptions = subscriptions;
        providers.forEach(provider -> this.providers.put(provider.provider(), provider));
        this.stripeWebhookSecret = stripeWebhookSecret;
        this.testWebhookSecret = testWebhookSecret;
    }

    /** Exactly one of {@code paymentMethodId} (a saved method) or {@code testOutcome} (TEST provider). */
    record PayRequest(UUID subscriptionId, UUID paymentMethodId, String testOutcome) {}

    record Result(SubscriptionPayment payment, boolean replayed) {}

    Result pay(UUID userId, PayRequest request, String idempotencyKey) {
        String hash = requestHash(request);
        Optional<SubscriptionPayment> earlier = payments.byIdempotencyKey(userId, idempotencyKey);
        if (earlier.isPresent()) return replay(earlier.get(), hash);

        SubscriptionClient.Terms terms = subscriptions.terms(request.subscriptionId());
        if (!terms.travelerId().equals(userId)) throw new PaymentDomain.NotFound();
        if (payments.bySubscription(request.subscriptionId()).isPresent()) throw alreadyExists();
        if (!terms.payable())
            throw new Failure(409, "SUBSCRIPTION_NOT_PAYABLE",
                    "This reservation can no longer be paid. If its 30-minute payment window passed, subscribe again.");
        if (terms.amountMinor() <= 0) throw new Failure(409, "NOTHING_TO_PAY", "This subscription has nothing to pay.");
        Instrument instrument = instrument(userId, request);

        Instant now = Instant.now();
        SubscriptionPayment created = new SubscriptionPayment(UUID.randomUUID(), request.subscriptionId(), userId,
                terms.amountMinor(), terms.currency(), instrument.provider(), instrument.methodId(), instrument.testScenario(),
                instrument.display(), idempotencyKey, hash, State.PROCESSING, null, null, false, null, null, 1, now, now);
        try {
            payments.insert(created);
        } catch (DuplicateKeyException race) {
            // A concurrent request won: same key -> return its payment; other key -> one payment per subscription.
            Optional<SubscriptionPayment> winner = payments.byIdempotencyKey(userId, idempotencyKey);
            if (winner.isPresent()) return replay(winner.get(), hash);
            throw alreadyExists();
        }
        log.info("Subscription payment {} recorded as PROCESSING for subscription {} via {}",
                created.paymentId(), created.subscriptionId(), created.provider());
        return new Result(attempt(created), false);
    }

    Optional<SubscriptionPayment> find(UUID paymentId) { return payments.byId(paymentId); }

    List<SubscriptionPayment> mine(UUID userId) { return payments.forUser(userId); }

    /** The caller's saved methods that can pay, and whether the TEST provider is offered. */
    record Options(List<PaymentDomain.Method> methods, Map<String, Boolean> providerReady, boolean testProviderEnabled) {}

    Options options(UUID userId) {
        List<PaymentDomain.Method> active = methods.methods(userId).stream().filter(method -> "ACTIVE".equals(method.status())).toList();
        Map<String, Boolean> ready = new HashMap<>();
        active.forEach(method -> ready.put(method.provider(), ready(method.provider())));
        return new Options(active, ready, ready(TestPaymentProvider.PROVIDER));
    }

    /**
     * Refund requested by engagement-service after a paid subscription was cancelled. Idempotent:
     * a payment already refunding or refunded is returned as it is. Empty if nothing was paid.
     */
    Optional<SubscriptionPayment> refundForSubscription(UUID subscriptionId) {
        Optional<SubscriptionPayment> found = payments.bySubscription(subscriptionId);
        if (found.isEmpty()) return found;
        SubscriptionPayment payment = found.get();
        if (payment.state() == State.SUCCEEDED) payment = move(payment, payment.refundPending("SUBSCRIPTION_CANCELLED"));
        // An unresolved payment is refunded when it resolves: engagement then refuses the success.
        return Optional.of(refund(payment));
    }

    /** Scheduled job body; see {@link SubscriptionPaymentStore#needingWork}. */
    int reconcile(Instant staleBefore) {
        int handled = 0;
        for (SubscriptionPayment payment : payments.needingWork(staleBefore)) {
            try {
                switch (payment.state()) {
                    case PROCESSING, REQUIRES_RECONCILIATION -> {
                        if (payment.attempts() >= MAX_ATTEMPTS) continue; // Left for operator review (logged when reached).
                        SubscriptionPayment claimed = payment.nextAttempt();
                        if (!payments.update(claimed, payment)) continue;
                        log.info("Reconciling subscription payment {} (attempt {})", payment.paymentId(), claimed.attempts());
                        attempt(claimed);
                    }
                    case SUCCEEDED, FAILED -> deliverOutcome(payment);
                    case REFUND_PENDING -> {
                        if (payment.attempts() >= MAX_ATTEMPTS) continue;
                        refund(payment);
                    }
                    default -> { }
                }
                handled++;
            } catch (RuntimeException ex) {
                log.warn("Reconciliation of subscription payment {} deferred: {}", payment.paymentId(), ex.getClass().getSimpleName());
            }
        }
        return handled;
    }

    /**
     * A signed provider callback. Only accepted when the signature is valid and fresh, the payment
     * exists for that provider, and amount and currency match what was charged. It can only resolve
     * a payment whose outcome is still unknown; a final state is never overwritten.
     */
    void webhook(String providerPath, String signature, String body) {
        String provider;
        String secret;
        switch (providerPath) {
            case "stripe" -> { provider = "STRIPE"; secret = stripeWebhookSecret; }
            case "test" -> { provider = TestPaymentProvider.PROVIDER; secret = ready(provider) ? testWebhookSecret : ""; }
            default -> throw new PaymentDomain.NotFound();
        }
        if (secret == null || secret.isBlank()) throw new PaymentDomain.NotFound();
        if (!WebhookSignature.valid(secret, signature, body, Instant.now()))
            throw new Failure(400, "INVALID_SIGNATURE", "Webhook signature verification failed");
        JsonNode event;
        try {
            event = JSON.readTree(body);
        } catch (RuntimeException ex) {
            throw new Failure(400, "INVALID_EVENT", "Webhook body is not a valid event");
        }
        String type = event.path("type").asString();
        boolean succeeded = "payment_intent.succeeded".equals(type);
        if (!succeeded && !"payment_intent.payment_failed".equals(type)) return;
        JsonNode object = event.path("data").path("object");
        UUID paymentId;
        try {
            paymentId = UUID.fromString(object.path("metadata").path("payment_id").asString());
        } catch (IllegalArgumentException ex) {
            return;
        }
        Optional<SubscriptionPayment> found = payments.byId(paymentId);
        if (found.isEmpty() || !found.get().provider().equals(provider)) return;
        SubscriptionPayment payment = found.get();
        if (object.path("amount").asLong(-1) != payment.amountMinor() || !payment.currency().equalsIgnoreCase(object.path("currency").asString())) {
            log.warn("Webhook for subscription payment {} ignored: amount or currency does not match", paymentId);
            return;
        }
        String reference = object.path("id").asString();
        if (succeeded && (reference == null || !reference.matches("[A-Za-z0-9_-]{3,255}"))) return;
        if (!payment.state().unresolved()) return;
        log.info("Verified {} webhook resolves subscription payment {}", provider, paymentId);
        deliverOutcome(move(payment, succeeded ? payment.with(State.SUCCEEDED, reference, null)
                : payment.with(State.FAILED, null, "PAYMENT_DECLINED")));
    }

    /** One provider call for a payment that is PROCESSING (just created or just claimed by reconciliation). */
    private SubscriptionPayment attempt(SubscriptionPayment payment) {
        ChargeProvider provider = providers.get(payment.provider());
        ChargeResult result;
        try {
            if (provider == null) throw new ProviderAdapter.Unavailable("PROVIDER_NOT_CONFIGURED");
            result = provider.charge(new ChargeRequest(payment.paymentId(), "subscription-payment-" + payment.paymentId(),
                    methodReference(payment), payment.amountMinor(), payment.currency(), payment.testScenario()));
        } catch (ProviderAdapter.Unavailable ambiguous) {
            // The provider may have charged. Never charge anew; reconciliation asks again with the same key.
            SubscriptionPayment pending = move(payment, payment.with(State.REQUIRES_RECONCILIATION, null, "PROVIDER_NO_ANSWER"));
            if (pending.attempts() >= MAX_ATTEMPTS)
                log.warn("Subscription payment {} still unresolved after {} attempts: operator review needed",
                        payment.paymentId(), pending.attempts());
            return pending;
        } catch (ProviderAdapter.Rejected rejected) {
            result = ChargeResult.declined("PAYMENT_REJECTED");
        }
        SubscriptionPayment next = result.succeeded() ? payment.with(State.SUCCEEDED, result.providerReference(), null)
                : payment.with(State.FAILED, null, result.failureCode());
        return deliverOutcome(move(payment, next));
    }

    /** Tells engagement-service once; if it is down the reconciliation job tells it later. */
    private SubscriptionPayment deliverOutcome(SubscriptionPayment payment) {
        if (payment.outcomeDelivered() || payment.state() != State.SUCCEEDED && payment.state() != State.FAILED) return payment;
        boolean succeeded = payment.state() == State.SUCCEEDED;
        return switch (subscriptions.reportOutcome(payment.subscriptionId(), succeeded, payment.paymentId())) {
            case ACCEPTED -> move(payment, payment.delivered());
            case UNAVAILABLE -> {
                log.warn("Outcome of subscription payment {} not yet delivered: engagement-service unavailable", payment.paymentId());
                yield payment;
            }
            case REJECTED -> succeeded
                    // Paid, but the reservation expired or was cancelled meanwhile: give the money back.
                    ? refund(move(payment, payment.refundPending("SUBSCRIPTION_NOT_PAYABLE")))
                    : move(payment, payment.delivered());
        };
    }

    private SubscriptionPayment refund(SubscriptionPayment payment) {
        if (payment.state() != State.REFUND_PENDING) return payment;
        SubscriptionPayment claimed = payment.refundAttempt();
        if (!payments.update(claimed, payment)) return current(payment);
        ChargeProvider provider = providers.get(payment.provider());
        try {
            if (provider == null) throw new ProviderAdapter.Unavailable("PROVIDER_NOT_CONFIGURED");
            String reference = provider.refund(new RefundRequest(payment.paymentId(), "subscription-refund-" + payment.paymentId(),
                    payment.providerReference(), payment.amountMinor(), payment.currency()));
            return move(claimed, claimed.refunded(reference));
        } catch (ProviderAdapter.Unavailable ex) {
            log.warn("Refund of subscription payment {} deferred: provider unavailable", payment.paymentId());
        } catch (ProviderAdapter.Rejected ex) {
            log.warn("Refund of subscription payment {} rejected by the provider: operator review needed", payment.paymentId());
        }
        return claimed;
    }

    /** Applies the change if nobody else changed the payment first; otherwise returns what they wrote. */
    private SubscriptionPayment move(SubscriptionPayment expected, SubscriptionPayment next) {
        if (!payments.update(next, expected)) return current(expected);
        if (next.state() != expected.state())
            log.info("Subscription payment {} {} -> {}", next.paymentId(), expected.state(), next.state());
        return next;
    }

    private SubscriptionPayment current(SubscriptionPayment payment) {
        return payments.byId(payment.paymentId()).orElse(payment);
    }

    private String methodReference(SubscriptionPayment payment) {
        if (payment.methodId() == null) return null;
        try {
            return methods.method(payment.methodId()).providerReference();
        } catch (PaymentDomain.NotFound ex) {
            throw new ProviderAdapter.Unavailable("METHOD_UNAVAILABLE");
        }
    }

    private record Instrument(String provider, UUID methodId, String testScenario, String display) {}

    private Instrument instrument(UUID userId, PayRequest request) {
        if (request.paymentMethodId() != null) {
            PaymentDomain.Method method = methods.method(request.paymentMethodId());
            // Another user's method gets the same answer as an unknown one: nothing is revealed.
            if (!method.userId().equals(userId)) throw new PaymentDomain.NotFound();
            if (!"ACTIVE".equals(method.status()))
                throw new Failure(409, "METHOD_NOT_ACTIVE", "This payment method cannot be used. Choose another one.");
            requireReady(method.provider());
            String display = method.displayMetadata() == null ? method.displayLabel() : method.displayMetadata();
            return new Instrument(method.provider(), method.id(), null, display);
        }
        requireReady(TestPaymentProvider.PROVIDER);
        return new Instrument(TestPaymentProvider.PROVIDER, null, request.testOutcome(),
                "Test card (simulated " + request.testOutcome().toLowerCase(Locale.ROOT) + ")");
    }

    private boolean ready(String provider) {
        ChargeProvider found = providers.get(provider);
        return found != null && found.configured();
    }

    private void requireReady(String provider) {
        if (!ready(provider))
            throw new Failure(503, "PROVIDER_NOT_CONFIGURED", "This payment provider is not available right now. Choose another method.");
    }

    private static Result replay(SubscriptionPayment earlier, String hash) {
        if (!earlier.requestHash().equals(hash))
            throw new Failure(409, "IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was used for a different payment request");
        return new Result(earlier, true);
    }

    private static Failure alreadyExists() {
        return new Failure(409, "PAYMENT_ALREADY_EXISTS", "This subscription already has a payment. Refresh to see its status.");
    }

    private static String requestHash(PayRequest request) {
        String canonical = request.subscriptionId() + "|" + request.paymentMethodId() + "|" + request.testOutcome();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** A business outcome with HTTP status, stable code and a safe message (no provider internals). */
    static final class Failure extends RuntimeException {
        final int status;
        final String code;

        Failure(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    /** engagement-service could not answer; nothing was charged. */
    static final class DependencyUnavailable extends RuntimeException {}
}
