package com.travelplan.engagement;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** One traveler's place on one travel, with a snapshot of the travel's terms when it was reserved. */
record Subscription(
        UUID subscriptionId,
        UUID travelId,
        UUID travelerId,
        UUID managerId,
        String travelTitle,
        LocalDate startDate,
        LocalDate endDate,
        String timeZone,
        LocalDate cancellationCutoff,
        long priceAmountMinor,
        String priceCurrency,
        Status status,
        UUID idempotencyKey,
        UUID paymentId,
        Instant reservationExpiresAt,
        String cancellationReason,
        UUID cancelledBy,
        Instant cancelledAt,
        boolean seatReleased,
        boolean refundDue,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * PENDING_PAYMENT -> CONFIRMED (payment succeeded) -> COMPLETED (travel ended).
     * PENDING_PAYMENT -> PAYMENT_FAILED. PENDING_PAYMENT or CONFIRMED -> CANCELLED_BY_TRAVELER
     * (until the cutoff) or CANCELLED_BY_MANAGER (any time, with a reason). Only the two active
     * states hold a place.
     */
    enum Status {
        PENDING_PAYMENT, CONFIRMED, PAYMENT_FAILED, CANCELLED_BY_TRAVELER, CANCELLED_BY_MANAGER, COMPLETED;

        static final Set<Status> ACTIVE = EnumSet.of(PENDING_PAYMENT, CONFIRMED);

        boolean active() { return ACTIVE.contains(this); }

        /** States in which the place has been (or must be) given back to the travel. */
        boolean releasesPlace() {
            return this == PAYMENT_FAILED || this == CANCELLED_BY_TRAVELER || this == CANCELLED_BY_MANAGER;
        }
    }

    static String activeKey(UUID travelId, UUID travelerId) {
        return travelId + ":" + travelerId;
    }
}
