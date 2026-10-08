package com.travelplan.payments;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payment figures for engagement-service's dashboards. Never routed by the gateway; the shared token filter
 * requires X-Internal-Service-Token. Amounts and provider names only, never payment details.
 */
@RestController
class PaymentInsightsController {
    /** Payments that went through, including those later refunded: the traveler did pay this way. */
    private static final Set<SubscriptionPayment.State> MADE = EnumSet.of(SubscriptionPayment.State.SUCCEEDED,
            SubscriptionPayment.State.REFUND_PENDING, SubscriptionPayment.State.REFUNDED);

    private final SubscriptionPaymentStore payments;

    PaymentInsightsController(SubscriptionPaymentStore payments) {
        this.payments = payments;
    }

    /** How often the user paid with each provider, most used first. */
    @GetMapping("/internal/users/{userId}/payment-usage")
    List<Usage> usage(@PathVariable UUID userId) {
        Map<String, Long> counts = payments.forUser(userId).stream().filter(p -> MADE.contains(p.state()))
                .collect(Collectors.groupingBy(SubscriptionPayment::provider, TreeMap::new, Collectors.counting()));
        return counts.entrySet().stream().map(entry -> new Usage(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(Usage::payments).reversed().thenComparing(Usage::provider)).toList();
    }

    /** Totals per currency: money kept (SUCCEEDED), being refunded, and refunded. */
    @GetMapping("/internal/payment-totals")
    List<Totals> totals() {
        Map<String, List<SubscriptionPayment>> byCurrency = payments.all().stream()
                .collect(Collectors.groupingBy(SubscriptionPayment::currency, TreeMap::new, Collectors.toList()));
        return byCurrency.entrySet().stream().map(entry -> {
            Function<SubscriptionPayment.State, Long> sum = state -> entry.getValue().stream()
                    .filter(p -> p.state() == state).mapToLong(SubscriptionPayment::amountMinor).sum();
            return new Totals(entry.getKey(), sum.apply(SubscriptionPayment.State.SUCCEEDED),
                    sum.apply(SubscriptionPayment.State.REFUND_PENDING), sum.apply(SubscriptionPayment.State.REFUNDED));
        }).toList();
    }

    record Usage(String provider, long payments) {}

    record Totals(String currency, long succeededMinor, long refundPendingMinor, long refundedMinor) {}
}
