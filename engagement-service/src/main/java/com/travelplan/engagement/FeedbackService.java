package com.travelplan.engagement;

import com.travelplan.common.security.AccessToken;
import com.travelplan.common.security.Role;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Feedback rules (docs/feedback-and-reports.md). A traveler may rate a travel once, and only after
 * taking part in it: the subscription is COMPLETED, or it is CONFIRMED and the travel's last day
 * has passed in the travel's own time zone (the housekeeping job has not marked it yet).
 * Feedback cannot be edited afterwards.
 */
@Service
class FeedbackService {
    private final FeedbackStore store;
    private final SubscriptionStore subscriptions;
    private final UserSummaryClient users;
    private final TravelReservationClient travels;
    private final Clock clock;

    FeedbackService(FeedbackStore store, SubscriptionStore subscriptions, UserSummaryClient users,
                    TravelReservationClient travels, Clock clock) {
        this.store = store;
        this.subscriptions = subscriptions;
        this.users = users;
        this.travels = travels;
        this.clock = clock;
    }

    /** True when this subscription shows the traveler took part in a travel that has ended. */
    static boolean eligible(Subscription subscription, Instant now) {
        if (subscription.status() == Subscription.Status.COMPLETED) return true;
        return subscription.status() == Subscription.Status.CONFIRMED
                && subscription.endDate().isBefore(CancellationPolicy.localDate(now, subscription.timeZone()));
    }

    FeedbackStore.Feedback submit(AccessToken caller, UUID travelId, int rating, String comment, UUID idempotencyKey) {
        store.requireAvailable();
        Optional<FeedbackStore.Feedback> earlier = store.byIdempotencyKey(caller.userId(), idempotencyKey);
        if (earlier.isPresent()) return replay(earlier.get(), travelId);

        Instant now = clock.instant();
        Subscription participation = subscriptions.forTravelerAndTravel(caller.userId(), travelId).stream()
                .filter(subscription -> eligible(subscription, now)).findFirst()
                .orElseThrow(() -> new SubscriptionService.Failure(409, "FEEDBACK_NOT_ELIGIBLE",
                        "You can rate a travel only after taking part in it"));
        if (store.exists(caller.userId(), travelId))
            throw alreadySubmitted();

        String author = users.find(caller.userId()).map(UserSummaryClient.UserSummary::displayName).orElse("Traveler");
        FeedbackStore.Feedback feedback = new FeedbackStore.Feedback(UUID.randomUUID(), participation.subscriptionId(),
                travelId, caller.userId(), participation.managerId(), participation.travelTitle(), author, rating,
                plainText(comment), idempotencyKey, now);
        try {
            store.insert(feedback);
            return feedback;
        } catch (DuplicateKeyException race) {
            Optional<FeedbackStore.Feedback> winner = store.byIdempotencyKey(caller.userId(), idempotencyKey);
            if (winner.isPresent()) return replay(winner.get(), travelId);
            throw alreadySubmitted();
        }
    }

    /**
     * Managers see feedback on their own travels only; a travel of another manager is 404. Admins see
     * everything and may filter by manager.
     */
    FeedbackStore.Page list(AccessToken caller, UUID travelId, UUID managerId, int page, int size) {
        store.requireAvailable();
        if (caller.role() == Role.ADMIN) return store.find(travelId, managerId, null, page, size);
        if (travelId != null && !travels.summary(travelId).managerId().equals(caller.userId()))
            throw new SubscriptionService.Failure(404, "NOT_FOUND", "Travel not found");
        return store.find(travelId, caller.userId(), null, page, size);
    }

    FeedbackStore.Page mine(AccessToken caller, int page, int size) {
        return store.find(null, null, caller.userId(), page, size);
    }

    /** Comments are kept as plain text: trimmed, empty becomes null. HTML is not interpreted anywhere. */
    static String plainText(String text) {
        if (text == null) return null;
        String trimmed = text.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static FeedbackStore.Feedback replay(FeedbackStore.Feedback earlier, UUID travelId) {
        if (!earlier.travelId().equals(travelId))
            throw new SubscriptionService.Failure(409, "IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was used for a different request");
        return earlier;
    }

    private static SubscriptionService.Failure alreadySubmitted() {
        return new SubscriptionService.Failure(409, "FEEDBACK_ALREADY_SUBMITTED", "You have already rated this travel");
    }
}
