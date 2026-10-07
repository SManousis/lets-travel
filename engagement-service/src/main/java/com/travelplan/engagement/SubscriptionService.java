package com.travelplan.engagement;

import com.travelplan.common.security.AccessToken;
import com.travelplan.common.security.Role;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Subscription rules. State lives in engagement's database; the place itself is reserved and
 * released in travel-service, which owns capacity. Every operation is safe to retry.
 */
@Service
class SubscriptionService {
    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
    static final Duration PAYMENT_WINDOW = Duration.ofMinutes(30);
    private final SubscriptionStore store;
    private final TravelReservationClient travels;
    private final UserSummaryClient users;
    private final PaymentRefundClient refunds;
    private final Clock clock;

    SubscriptionService(SubscriptionStore store, TravelReservationClient travels, UserSummaryClient users,
                        PaymentRefundClient refunds, Clock clock) {
        this.store = store;
        this.travels = travels;
        this.users = users;
        this.refunds = refunds;
        this.clock = clock;
    }

    /** Result of a subscribe call; {@code replayed} is true when an earlier request with the same key is returned. */
    record Subscribed(Subscription subscription, boolean replayed) {}

    /**
     * Validates the traveler, reserves a place atomically in travel-service and records a
     * PENDING_PAYMENT subscription. A retry with the same Idempotency-Key returns the first result.
     */
    Subscribed subscribe(AccessToken caller, UUID travelId, UUID idempotencyKey) {
        store.requireAvailable();
        Optional<Subscription> earlier = store.byIdempotencyKey(caller.userId(), idempotencyKey);
        if (earlier.isPresent()) return replay(earlier.get(), travelId);
        if (store.active(travelId, caller.userId()).isPresent())
            throw new Failure(409, "ALREADY_SUBSCRIBED", "You already hold a place on this travel");
        UserSummaryClient.UserSummary traveler = users.find(caller.userId())
                .filter(UserSummaryClient.UserSummary::active)
                .orElseThrow(() -> new Failure(409, "TRAVELER_NOT_ACTIVE", "This account cannot book travels"));

        TravelReservationClient.TravelSnapshot travel = travels.reserve(travelId, traveler.userId());
        Instant now = clock.instant();
        Subscription created = new Subscription(UUID.randomUUID(), travelId, caller.userId(), travel.managerId(),
                travel.title(), travel.startDate(), travel.endDate(), travel.timeZone(),
                CancellationPolicy.cutoff(travel.startDate()), amountMinor(travel.price(), travel.currency()),
                travel.currency(), Subscription.Status.PENDING_PAYMENT, idempotencyKey, null, now.plus(PAYMENT_WINDOW),
                null, null, null, false, false, 1, now, now);
        try {
            store.insert(created);
            return new Subscribed(created, false);
        } catch (DuplicateKeyException race) {
            // A concurrent request won. The place it reserved is the same one (travel-service keys it by
            // traveler), so nothing is released here.
            Optional<Subscription> winner = store.byIdempotencyKey(caller.userId(), idempotencyKey);
            if (winner.isPresent()) return replay(winner.get(), travelId);
            throw new Failure(409, "ALREADY_SUBSCRIBED", "You already hold a place on this travel");
        } catch (DataAccessException storeFailure) {
            releaseQuietly(travelId, caller.userId());
            throw new ManagerPageService.DependencyUnavailable();
        }
    }

    /** Traveler cancellation, allowed up to and including the cutoff date in the travel's time zone. */
    Subscription cancelOwn(AccessToken caller, UUID subscriptionId) {
        Subscription subscription = store.byId(subscriptionId)
                .filter(found -> found.travelerId().equals(caller.userId()))
                .orElseThrow(Failure::notFound);
        if (subscription.status() == Subscription.Status.CANCELLED_BY_TRAVELER) return finishSideEffects(subscription);
        if (!subscription.status().active())
            throw new Failure(409, "SUBSCRIPTION_NOT_ACTIVE", "This subscription is no longer active");
        if (!CancellationPolicy.travelerMayCancel(subscription.startDate(), subscription.timeZone(), clock.instant()))
            throw new Failure(409, "CANCELLATION_CUTOFF_PASSED",
                    "Cancellation closed on " + subscription.cancellationCutoff() + " (3 days before the start)");
        return change(subscription, Subscription.Status.ACTIVE, new SubscriptionStore.Change(Subscription.Status.CANCELLED_BY_TRAVELER,
                caller.userId(), null, null, "SubscriptionCancelled"));
    }

    /** Manager or admin removal of a subscriber. Not bound by the traveler cutoff; needs a reason. */
    Subscription removeByManager(AccessToken caller, UUID travelId, UUID subscriptionId, String reason) {
        requireManagedTravel(caller, travelId);
        Subscription subscription = store.byId(subscriptionId)
                .filter(found -> found.travelId().equals(travelId))
                .orElseThrow(Failure::notFound);
        if (subscription.status() == Subscription.Status.CANCELLED_BY_MANAGER) return finishSideEffects(subscription);
        if (!subscription.status().active())
            throw new Failure(409, "SUBSCRIPTION_NOT_ACTIVE", "This subscription is no longer active");
        return change(subscription, Subscription.Status.ACTIVE, new SubscriptionStore.Change(Subscription.Status.CANCELLED_BY_MANAGER,
                caller.userId(), reason, null, "SubscriptionCancelled"));
    }

    Subscription get(AccessToken caller, UUID subscriptionId) {
        return store.byId(subscriptionId).filter(found -> found.travelerId().equals(caller.userId())
                        || caller.role() == Role.ADMIN
                        || caller.role() == Role.TRAVEL_MANAGER && found.managerId().equals(caller.userId()))
                .orElseThrow(Failure::notFound);
    }

    SubscriptionStore.Page mine(AccessToken caller, Subscription.Status status, int page, int size) {
        return store.mine(caller.userId(), status, page, size);
    }

    record Subscriber(Subscription subscription, String displayName, LocalDate memberSince, long completedTrips) {}

    record SubscriberPage(List<Subscriber> items, long total) {}

    /** Subscribers of a travel the caller manages, with safe profile summaries. */
    SubscriberPage subscribers(AccessToken caller, UUID travelId, Subscription.Status status, int page, int size) {
        store.requireAvailable();
        requireManagedTravel(caller, travelId);
        SubscriptionStore.Page found = store.forTravel(travelId, status, page, size);
        Map<UUID, Optional<UserSummaryClient.UserSummary>> profiles = new HashMap<>();
        List<Subscriber> items = found.items().stream().map(subscription -> {
            Optional<UserSummaryClient.UserSummary> profile =
                    profiles.computeIfAbsent(subscription.travelerId(), users::find);
            return new Subscriber(subscription, profile.map(UserSummaryClient.UserSummary::displayName).orElse("Former traveler"),
                    profile.map(summary -> LocalDate.ofInstant(summary.memberSince(), java.time.ZoneOffset.UTC)).orElse(null),
                    store.completedTrips(subscription.travelerId()));
        }).toList();
        return new SubscriberPage(items, found.total());
    }

    /** What payment-service needs before charging: owner, price snapshot and whether payment is still accepted. */
    record PaymentTerms(UUID subscriptionId, UUID travelerId, Subscription.Status status, long amountMinor,
                        String currency, Instant reservationExpiresAt, boolean payable) {}

    PaymentTerms paymentTerms(UUID subscriptionId) {
        Subscription subscription = store.byId(subscriptionId).orElseThrow(Failure::notFound);
        boolean payable = subscription.status() == Subscription.Status.PENDING_PAYMENT
                && subscription.reservationExpiresAt() != null
                && clock.instant().isBefore(subscription.reservationExpiresAt());
        return new PaymentTerms(subscription.subscriptionId(), subscription.travelerId(), subscription.status(),
                subscription.priceAmountMinor(), subscription.priceCurrency(),
                subscription.status() == Subscription.Status.PENDING_PAYMENT ? subscription.reservationExpiresAt() : null,
                payable);
    }

    /**
     * Ends unpaid reservations whose 30-minute payment window has passed: PENDING_PAYMENT becomes
     * PAYMENT_FAILED (history reason RESERVATION_EXPIRED) and the place is given back. A payment
     * that still succeeds afterwards is answered with 409 and refunded by payment-service.
     */
    int expireUnpaidReservations() {
        int expired = 0;
        for (Subscription subscription : store.pendingExpiredBy(clock.instant())) {
            Optional<Subscription> changed = store.transition(subscription.subscriptionId(),
                    EnumSet.of(Subscription.Status.PENDING_PAYMENT), new SubscriptionStore.Change(Subscription.Status.PAYMENT_FAILED,
                            null, "RESERVATION_EXPIRED", null, "SubscriptionPaymentFailed"), clock.instant());
            if (changed.isPresent()) {
                finishRelease(changed.get());
                expired++;
            }
        }
        return expired;
    }

    /** Asks payment-service again for refunds it has not accepted yet. */
    void retryDueRefunds() {
        store.refundsDue().forEach(this::finishRefund);
    }

    /** Called by payment-service: confirm after a verified payment, or release the place after a failed one. */
    Subscription paymentOutcome(UUID subscriptionId, boolean succeeded, UUID paymentId) {
        Subscription subscription = store.byId(subscriptionId).orElseThrow(Failure::notFound);
        Subscription.Status target = succeeded ? Subscription.Status.CONFIRMED : Subscription.Status.PAYMENT_FAILED;
        if (subscription.status() == target) {
            if (succeeded && !paymentId.equals(subscription.paymentId()))
                throw new Failure(409, "PAYMENT_MISMATCH", "Subscription was confirmed by another payment");
            return succeeded ? subscription : finishRelease(subscription);
        }
        if (subscription.status() != Subscription.Status.PENDING_PAYMENT)
            throw new Failure(409, "SUBSCRIPTION_NOT_PENDING", "Subscription is not waiting for payment");
        return change(subscription, EnumSet.of(Subscription.Status.PENDING_PAYMENT), new SubscriptionStore.Change(target, null, null, paymentId,
                succeeded ? "SubscriptionConfirmed" : "SubscriptionPaymentFailed"));
    }

    /** Marks confirmed subscriptions COMPLETED once their travel's last day has passed in its own time zone. */
    int completeFinishedTravels() {
        Instant now = clock.instant();
        int completed = 0;
        for (Subscription subscription : store.confirmedEndingBy(LocalDate.ofInstant(now, java.time.ZoneOffset.UTC).plusDays(1))) {
            if (!subscription.endDate().isBefore(CancellationPolicy.localDate(now, subscription.timeZone()))) continue;
            if (store.transition(subscription.subscriptionId(), EnumSet.of(Subscription.Status.CONFIRMED),
                    new SubscriptionStore.Change(Subscription.Status.COMPLETED, null, null, null, "SubscriptionCompleted"),
                    now).isPresent()) completed++;
        }
        return completed;
    }

    /** Retries place releases that travel-service did not confirm earlier. */
    void retryUnreleasedPlaces() {
        store.unreleasedPlaces().forEach(this::finishRelease);
    }

    private Subscription change(Subscription subscription, java.util.Set<Subscription.Status> from,
                                SubscriptionStore.Change change) {
        Optional<Subscription> updated = store.transition(subscription.subscriptionId(), from, change, clock.instant());
        if (updated.isEmpty()) {
            // Another request changed it first; answer like a retry of that request would.
            Subscription now = store.byId(subscription.subscriptionId()).orElseThrow(Failure::notFound);
            if (now.status() == change.to()) return finishSideEffects(now);
            throw new Failure(409, "SUBSCRIPTION_NOT_ACTIVE", "This subscription is no longer active");
        }
        return finishSideEffects(updated.get());
    }

    private Subscription finishSideEffects(Subscription subscription) {
        finishRelease(subscription);
        finishRefund(subscription);
        return subscription;
    }

    /**
     * Cancelling a paid subscription commits refund_due = TRUE with the status change; the request to
     * payment-service follows. If payment-service is down the flag stays set and housekeeping retries.
     */
    private void finishRefund(Subscription subscription) {
        if (!subscription.refundDue()) return;
        try {
            refunds.requestRefund(subscription.subscriptionId());
            store.markRefundRequested(subscription.subscriptionId());
        } catch (ManagerPageService.DependencyUnavailable ex) {
            log.warn("Refund request for subscription {} deferred: payment-service unavailable", subscription.subscriptionId());
        }
    }

    /**
     * The status change is committed first and the place given back afterwards, so a failure in
     * between can only leave a place held too long (never an oversold travel). A retry, or the
     * scheduled job, finishes the release.
     */
    private Subscription finishRelease(Subscription subscription) {
        if (!subscription.status().releasesPlace() || subscription.seatReleased()) return subscription;
        try {
            // If the traveler has subscribed again since, the place now belongs to that subscription.
            if (store.active(subscription.travelId(), subscription.travelerId()).isEmpty())
                travels.release(subscription.travelId(), subscription.travelerId());
            store.markSeatReleased(subscription.subscriptionId());
        } catch (ManagerPageService.DependencyUnavailable ex) {
            log.warn("Place release for subscription {} deferred: travel-service unavailable", subscription.subscriptionId());
        }
        return subscription;
    }

    private void releaseQuietly(UUID travelId, UUID travelerId) {
        try {
            travels.release(travelId, travelerId);
        } catch (RuntimeException ex) {
            log.warn("Could not give back place on travel {} after a failed subscription write", travelId);
        }
    }

    private void requireManagedTravel(AccessToken caller, UUID travelId) {
        TravelReservationClient.TravelSnapshot travel = travels.summary(travelId);
        if (caller.role() != Role.ADMIN && !(caller.role() == Role.TRAVEL_MANAGER && travel.managerId().equals(caller.userId())))
            throw Failure.notFound();
    }

    private static Subscribed replay(Subscription earlier, UUID travelId) {
        if (!earlier.travelId().equals(travelId))
            throw new Failure(409, "IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was used for a different request");
        return new Subscribed(earlier, true);
    }

    static long amountMinor(BigDecimal price, String currency) {
        int digits;
        try {
            digits = Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
        } catch (IllegalArgumentException unknown) {
            digits = 2;
        }
        return price.setScale(digits, RoundingMode.HALF_UP).movePointRight(digits).longValueExact();
    }

    /** A business-rule outcome with its HTTP status and stable error code. */
    static final class Failure extends RuntimeException {
        final int status;
        final String code;

        Failure(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        static Failure notFound() { return new Failure(404, "NOT_FOUND", "Subscription not found"); }
    }
}
