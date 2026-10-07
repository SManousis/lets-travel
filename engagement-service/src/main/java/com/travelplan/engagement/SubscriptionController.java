package com.travelplan.engagement;

import com.travelplan.common.security.AccessToken;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Traveler subscriptions and manager subscriber lists (docs/api/engagement.yaml). */
@RestController
class SubscriptionController {
    private final SubscriptionService subscriptions;
    private final Clock clock;

    SubscriptionController(SubscriptionService subscriptions, Clock clock) {
        this.subscriptions = subscriptions;
        this.clock = clock;
    }

    @PostMapping("/api/subscriptions")
    ResponseEntity<SubscriptionView> subscribe(@AuthenticationPrincipal AccessToken caller,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                               @Valid @RequestBody SubscriptionCreate body) {
        UUID idempotencyKey = idempotencyKey(key);
        SubscriptionService.Subscribed result = subscriptions.subscribe(caller, body.travelId(), idempotencyKey);
        SubscriptionView view = view(result.subscription());
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/subscriptions/" + view.subscriptionId())).body(view);
    }

    @GetMapping("/api/subscriptions")
    PageView<SubscriptionView> mine(@AuthenticationPrincipal AccessToken caller,
                                    @RequestParam(required = false) Subscription.Status status,
                                    @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        checkPage(page, size);
        SubscriptionStore.Page found = subscriptions.mine(caller, status, page, size);
        return PageView.of(found.items().stream().map(this::view).toList(), page, size, found.total());
    }

    @GetMapping("/api/subscriptions/{subscriptionId}")
    SubscriptionView get(@AuthenticationPrincipal AccessToken caller, @PathVariable UUID subscriptionId) {
        return view(subscriptions.get(caller, subscriptionId));
    }

    @PostMapping("/api/subscriptions/{subscriptionId}/cancellation")
    SubscriptionView cancel(@AuthenticationPrincipal AccessToken caller, @PathVariable UUID subscriptionId,
                            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        idempotencyKey(key);
        return view(subscriptions.cancelOwn(caller, subscriptionId));
    }

    @GetMapping("/api/managed-travels/{travelId}/subscribers")
    PageView<SubscriberView> subscribers(@AuthenticationPrincipal AccessToken caller, @PathVariable UUID travelId,
                                         @RequestParam(required = false) Subscription.Status status,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        checkPage(page, size);
        SubscriptionService.SubscriberPage found = subscriptions.subscribers(caller, travelId, status, page, size);
        return PageView.of(found.items().stream().map(SubscriptionController::subscriberView).toList(), page, size, found.total());
    }

    @PostMapping("/api/managed-travels/{travelId}/subscribers/{subscriptionId}/removal")
    SubscriptionView remove(@AuthenticationPrincipal AccessToken caller, @PathVariable UUID travelId,
                            @PathVariable UUID subscriptionId,
                            @RequestHeader(value = "Idempotency-Key", required = false) String key,
                            @Valid @RequestBody RemovalRequest body) {
        idempotencyKey(key);
        String reason = body.reason().trim();
        if (reason.length() < 3) throw new SubscriptionService.Failure(400, "VALIDATION_ERROR", "A reason of at least 3 characters is required");
        return view(subscriptions.removeByManager(caller, travelId, subscriptionId, reason));
    }

    private SubscriptionView view(Subscription subscription) {
        boolean mayCancel = subscription.status().active()
                && CancellationPolicy.travelerMayCancel(subscription.startDate(), subscription.timeZone(), clock.instant());
        return new SubscriptionView(subscription.subscriptionId(), subscription.travelId(), subscription.travelTitle(),
                subscription.travelerId(), subscription.managerId(), subscription.status(),
                new Money(subscription.priceAmountMinor(), subscription.priceCurrency()), subscription.startDate(),
                subscription.endDate(), subscription.timeZone(), subscription.cancellationCutoff(), mayCancel,
                subscription.paymentId(),
                subscription.status() == Subscription.Status.PENDING_PAYMENT ? subscription.reservationExpiresAt() : null,
                subscription.cancellationReason(), subscription.cancelledAt(), subscription.createdAt(), subscription.updatedAt());
    }

    private static SubscriberView subscriberView(SubscriptionService.Subscriber subscriber) {
        Subscription subscription = subscriber.subscription();
        return new SubscriberView(subscription.subscriptionId(), subscription.travelerId(), subscriber.displayName(),
                subscriber.memberSince(), subscriber.completedTrips(), subscriber.reportCount(), subscription.status(),
                subscription.createdAt(), subscription.cancellationReason(), subscription.cancelledAt());
    }

    static UUID idempotencyKey(String raw) {
        try {
            if (raw != null) return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            // Reported below.
        }
        throw new SubscriptionService.Failure(400, "VALIDATION_ERROR", "Idempotency-Key header must be a UUID");
    }

    static void checkPage(int page, int size) {
        if (page < 0 || size < 1 || size > 100)
            throw new SubscriptionService.Failure(400, "VALIDATION_ERROR", "page must be >= 0 and size between 1 and 100");
    }

    record SubscriptionCreate(@NotNull UUID travelId) {}

    record RemovalRequest(@NotBlank @Size(min = 3, max = 500) String reason) {}

    record Money(long amountMinor, String currency) {}

    record SubscriptionView(UUID subscriptionId, UUID travelId, String travelTitle, UUID travelerId, UUID managerId,
                            Subscription.Status status, Money price, LocalDate startDate, LocalDate endDate,
                            String timeZone, LocalDate cancellationCutoff, boolean travelerMayCancel, UUID paymentId,
                            Instant reservationExpiresAt, String cancellationReason, Instant cancelledAt,
                            Instant createdAt, Instant updatedAt) {}

    /** Safe profile summary for the travel's manager: no contact, identity-document or payment details. */
    record SubscriberView(UUID subscriptionId, UUID travelerId, String displayName, LocalDate memberSince,
                          long completedTrips, long reportCount, Subscription.Status status, Instant subscribedAt,
                          String cancellationReason, Instant cancelledAt) {}

    record PageView<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
        static <T> PageView<T> of(List<T> items, int page, int size, long total) {
            return new PageView<>(items, page, size, total, (int) ((total + size - 1) / size));
        }
    }
}
