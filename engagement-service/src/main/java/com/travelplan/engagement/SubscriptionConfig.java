package com.travelplan.engagement;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Configuration
@EnableScheduling
class SubscriptionConfig {

    /** Injected so the cancellation cutoff can be tested at exact instants. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Housekeeping: completes finished travels, finishes place releases and refund requests that failed earlier. */
    @Component
    static class SubscriptionHousekeeping {
        private static final Logger log = LoggerFactory.getLogger(SubscriptionHousekeeping.class);
        private final SubscriptionService subscriptions;
        private final SubscriptionStore store;

        SubscriptionHousekeeping(SubscriptionService subscriptions, SubscriptionStore store) {
            this.subscriptions = subscriptions;
            this.store = store;
        }

        @Scheduled(fixedDelayString = "${subscriptions.housekeeping-interval:PT15M}",
                initialDelayString = "${subscriptions.housekeeping-initial-delay:PT1M}")
        void run() {
            try {
                store.requireAvailable();
                subscriptions.retryUnreleasedPlaces();
                subscriptions.retryDueRefunds();
                int completed = subscriptions.completeFinishedTravels();
                if (completed > 0) log.info("Marked {} subscriptions completed", completed);
            } catch (RuntimeException ex) {
                log.warn("Subscription housekeeping skipped: {}", ex.getClass().getSimpleName());
            }
        }

        /** Enforces the 30-minute payment window every minute, so an unpaid place is held at most ~31 minutes. */
        @Scheduled(fixedDelayString = "${subscriptions.expiry-interval:PT1M}",
                initialDelayString = "${subscriptions.housekeeping-initial-delay:PT1M}")
        void expireReservations() {
            try {
                store.requireAvailable();
                int expired = subscriptions.expireUnpaidReservations();
                if (expired > 0) log.info("Expired {} unpaid reservations", expired);
            } catch (RuntimeException ex) {
                log.warn("Reservation expiry skipped: {}", ex.getClass().getSimpleName());
            }
        }
    }
}
