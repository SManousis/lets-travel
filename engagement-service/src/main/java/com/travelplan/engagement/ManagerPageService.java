package com.travelplan.engagement;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The public manager page: profile and listing counts from user-service and travel-service, ratings
 * and the upheld report count from engagement's own database. Ratings never name their author and
 * report contents and reporters are never shown.
 */
@Service
final class ManagerPageService {
    static final int RECENT_RATINGS = 10;
    private final UserManagerClient users;
    private final TravelManagerClient travels;
    private final FeedbackStore feedback;
    private final ReportStore reports;

    ManagerPageService(UserManagerClient users, TravelManagerClient travels, FeedbackStore feedback, ReportStore reports) {
        this.users = users;
        this.travels = travels;
        this.feedback = feedback;
        this.reports = reports;
    }

    ManagerPage page(UUID managerId) {
        UserManagerClient.ManagerSummary manager = users.get(managerId);
        TravelManagerClient.TravelStatistics statistics = travels.get(managerId);
        FeedbackStore.RatingSummary rating = feedback.ratingFor(managerId);
        List<PublicRating> recent = feedback.recentFor(managerId, RECENT_RATINGS).stream()
                .map(item -> new PublicRating(item.travelTitle(), item.rating(), item.comment(), item.createdAt())).toList();
        Double average = rating.average() == null ? null : Math.round(rating.average() * 10) / 10.0;
        return new ManagerPage(manager.managerId(), manager.displayName(), manager.memberSince(),
                statistics.completedTravelCount(), statistics.totalTravelCount(), average, rating.count(), recent,
                reports.upheldAgainst(ReportStore.TargetType.TRAVEL_MANAGER, managerId));
    }

    record ManagerPage(UUID managerId, String displayName, Instant memberSince, long completedTravels,
                       long totalTravels, Double averageRating, long ratingCount, List<PublicRating> recentRatings,
                       long reportCount) {}

    /** A rating as shown publicly: no author. */
    record PublicRating(String travelTitle, int rating, String comment, Instant createdAt) {}

    static final class ManagerNotFound extends RuntimeException {}
    static final class DependencyUnavailable extends RuntimeException {}
}
