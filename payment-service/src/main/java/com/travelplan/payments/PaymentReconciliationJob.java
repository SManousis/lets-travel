package com.travelplan.payments;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Every minute: resolves payments whose provider answer was lost (by replaying the same provider
 * idempotency key), delivers outcomes engagement-service missed, and retries pending refunds.
 */
@Configuration
@EnableScheduling
class PaymentReconciliationJob {
    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationJob.class);
    private final SubscriptionPaymentService payments;

    PaymentReconciliationJob(SubscriptionPaymentService payments) {
        this.payments = payments;
    }

    @Scheduled(fixedDelayString = "${payments.reconciliation-interval:PT1M}",
            initialDelayString = "${payments.reconciliation-initial-delay:PT1M}")
    void run() {
        try {
            int handled = payments.reconcile(Instant.now().minus(SubscriptionPaymentService.STALE_PROCESSING));
            if (handled > 0) log.info("Payment reconciliation handled {} payments", handled);
        } catch (RuntimeException ex) {
            log.warn("Payment reconciliation skipped: {}", ex.getClass().getSimpleName());
        }
    }
}
