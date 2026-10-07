package com.travelplan.engagement;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Called by payment-service (Phase 4) once a payment is verified or has terminally failed. Never
 * routed by the gateway; the shared token filter requires X-Internal-Service-Token. Repeating the
 * same outcome returns the same subscription.
 */
@RestController
class SubscriptionInternalController {
    private final SubscriptionService subscriptions;

    SubscriptionInternalController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    /** payment-service reads the owner and the price snapshot here; it never trusts an amount from the browser. */
    @GetMapping("/internal/subscriptions/{subscriptionId}")
    SubscriptionService.PaymentTerms paymentTerms(@PathVariable UUID subscriptionId) {
        return subscriptions.paymentTerms(subscriptionId);
    }

    @PostMapping("/internal/subscriptions/{subscriptionId}/payment-outcome")
    Outcome paymentOutcome(@PathVariable UUID subscriptionId, @Valid @RequestBody PaymentOutcome body) {
        Subscription result = subscriptions.paymentOutcome(subscriptionId,
                body.outcome() == PaymentOutcome.Result.SUCCEEDED, body.paymentId());
        return new Outcome(result.subscriptionId(), result.status(), result.paymentId());
    }

    record PaymentOutcome(@NotNull Result outcome, @NotNull UUID paymentId) {
        enum Result { SUCCEEDED, FAILED }
    }

    record Outcome(UUID subscriptionId, Subscription.Status status, UUID paymentId) {}
}
