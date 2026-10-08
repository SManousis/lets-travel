package com.travelplan.engagement.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The source records every dashboard figure is computed from, read straight from engagement-service's
 * tables. Nothing is stored twice, so a figure can always be traced back to these rows.
 */
public record DashboardFacts(List<SubscriptionFact> subscriptions, List<FeedbackFact> feedback, List<ReportFact> reports) {

    public DashboardFacts {
        subscriptions = List.copyOf(subscriptions);
        feedback = List.copyOf(feedback);
        reports = List.copyOf(reports);
    }

    /**
     * One subscription. {@code paidAt} is when it became CONFIRMED (payment verified); {@code refundedAt} when a
     * paid subscription was cancelled, which always means a full refund. Both are null when it never happened.
     */
    public record SubscriptionFact(UUID subscriptionId, UUID travelId, UUID managerId, String travelTitle,
                                   LocalDate startDate, LocalDate endDate, UUID travelerId, String status,
                                   long amountMinor, String currency, Instant paidAt, Instant refundedAt) {
        boolean paid() {
            return paidAt != null;
        }

        boolean refunded() {
            return refundedAt != null;
        }

        /** Confirmed or completed now: the traveler takes (or took) part. */
        boolean participating() {
            return "CONFIRMED".equals(status) || "COMPLETED".equals(status);
        }
    }

    public record FeedbackFact(UUID travelId, UUID managerId, UUID travelerId, int rating, Instant createdAt) {}

    /** A report; only its target, reporter and outcome are used, never the description. */
    public record ReportFact(UUID reporterId, String targetType, UUID targetId, String status, Instant createdAt) {
        boolean upheld() {
            return "RESOLVED".equals(status);
        }

        boolean open() {
            return "OPEN".equals(status) || "IN_REVIEW".equals(status);
        }
    }
}
