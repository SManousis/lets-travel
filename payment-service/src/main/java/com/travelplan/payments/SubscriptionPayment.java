package com.travelplan.payments;

import java.time.Instant;
import java.util.UUID;

/**
 * The payment for one subscription. Only safe data: the saved method is referenced by ID and
 * shown through {@code methodDisplay}; card numbers and provider secrets are never stored.
 */
record SubscriptionPayment(
        UUID paymentId,
        UUID subscriptionId,
        UUID userId,
        long amountMinor,
        String currency,
        String provider,
        UUID methodId,
        String testScenario,
        String methodDisplay,
        String idempotencyKey,
        String requestHash,
        State state,
        String providerReference,
        String failureCode,
        boolean outcomeDelivered,
        String refundReason,
        String refundReference,
        int attempts,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * PROCESSING (saved before the provider call) -> SUCCEEDED | FAILED, or -> REQUIRES_RECONCILIATION
     * when the provider's answer was lost (timeout, 5xx). Reconciliation replays the same request
     * with the same provider idempotency key, so it can never create a second charge.
     * SUCCEEDED -> REFUND_PENDING -> REFUNDED when the subscription is cancelled or could not be confirmed.
     */
    enum State {
        PROCESSING, REQUIRES_RECONCILIATION, SUCCEEDED, FAILED, REFUND_PENDING, REFUNDED;

        boolean unresolved() { return this == PROCESSING || this == REQUIRES_RECONCILIATION; }
    }

    SubscriptionPayment with(State nextState, String nextProviderReference, String nextFailureCode) {
        return new SubscriptionPayment(paymentId, subscriptionId, userId, amountMinor, currency, provider, methodId,
                testScenario, methodDisplay, idempotencyKey, requestHash, nextState, nextProviderReference,
                nextFailureCode, outcomeDelivered, refundReason, refundReference, attempts, createdAt, Instant.now());
    }

    SubscriptionPayment nextAttempt() {
        return new SubscriptionPayment(paymentId, subscriptionId, userId, amountMinor, currency, provider, methodId,
                testScenario, methodDisplay, idempotencyKey, requestHash, State.PROCESSING, providerReference,
                failureCode, outcomeDelivered, refundReason, refundReference, attempts + 1, createdAt, Instant.now());
    }

    SubscriptionPayment delivered() {
        return new SubscriptionPayment(paymentId, subscriptionId, userId, amountMinor, currency, provider, methodId,
                testScenario, methodDisplay, idempotencyKey, requestHash, state, providerReference, failureCode,
                true, refundReason, refundReference, attempts, createdAt, Instant.now());
    }

    SubscriptionPayment refundPending(String reason) {
        return new SubscriptionPayment(paymentId, subscriptionId, userId, amountMinor, currency, provider, methodId,
                testScenario, methodDisplay, idempotencyKey, requestHash, State.REFUND_PENDING, providerReference,
                null, true, reason, refundReference, attempts, createdAt, Instant.now());
    }

    SubscriptionPayment refundAttempt() {
        return new SubscriptionPayment(paymentId, subscriptionId, userId, amountMinor, currency, provider, methodId,
                testScenario, methodDisplay, idempotencyKey, requestHash, state, providerReference, failureCode,
                outcomeDelivered, refundReason, refundReference, attempts + 1, createdAt, Instant.now());
    }

    SubscriptionPayment refunded(String reference) {
        return new SubscriptionPayment(paymentId, subscriptionId, userId, amountMinor, currency, provider, methodId,
                testScenario, methodDisplay, idempotencyKey, requestHash, State.REFUNDED, providerReference, null,
                outcomeDelivered, refundReason, reference, attempts, createdAt, Instant.now());
    }
}
