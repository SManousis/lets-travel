package com.travelplan.payments;

import com.travelplan.payments.SubscriptionPayment.State;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentInsightsControllerTest {
    private final SubscriptionPaymentStore store = new SubscriptionPaymentStore((JdbcTemplate) null);
    private final PaymentInsightsController insights = new PaymentInsightsController(store);
    private final UUID user = UUID.randomUUID();

    @Test void usageCountsPaymentsThatWentThroughMostUsedFirst() {
        store.insert(payment(user, "PAYPAL", State.SUCCEEDED, 10000, "EUR"));
        store.insert(payment(user, "STRIPE", State.SUCCEEDED, 10000, "EUR"));
        store.insert(payment(user, "STRIPE", State.REFUNDED, 10000, "EUR"));
        store.insert(payment(user, "TEST", State.FAILED, 10000, "EUR"));          // never went through
        store.insert(payment(UUID.randomUUID(), "PAYPAL", State.SUCCEEDED, 10000, "EUR")); // someone else

        assertThat(insights.usage(user)).containsExactly(
                new PaymentInsightsController.Usage("STRIPE", 2), new PaymentInsightsController.Usage("PAYPAL", 1));
    }

    @Test void totalsSeparateKeptRefundingAndRefundedMoneyPerCurrency() {
        store.insert(payment(user, "STRIPE", State.SUCCEEDED, 50000, "EUR"));
        store.insert(payment(user, "STRIPE", State.SUCCEEDED, 25000, "EUR"));
        store.insert(payment(user, "STRIPE", State.REFUND_PENDING, 10000, "EUR"));
        store.insert(payment(user, "PAYPAL", State.REFUNDED, 30000, "EUR"));
        store.insert(payment(user, "PAYPAL", State.SUCCEEDED, 40000, "USD"));
        store.insert(payment(user, "TEST", State.PROCESSING, 99999, "EUR"));

        assertThat(insights.totals()).containsExactly(
                new PaymentInsightsController.Totals("EUR", 75000, 10000, 30000),
                new PaymentInsightsController.Totals("USD", 40000, 0, 0));
    }

    private static SubscriptionPayment payment(UUID user, String provider, State state, long amount, String currency) {
        Instant now = Instant.now();
        return new SubscriptionPayment(UUID.randomUUID(), UUID.randomUUID(), user, amount, currency, provider, null, null,
                "Card", UUID.randomUUID().toString(), "hash", state, null, null, true, null, null, 1, now, now);
    }
}
