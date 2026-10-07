package com.travelplan.engagement;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
final class ManagerPageService {
    private final UserManagerClient users;
    private final TravelManagerClient travels;

    ManagerPageService(UserManagerClient users, TravelManagerClient travels) {
        this.users = users;
        this.travels = travels;
    }

    ManagerPage page(UUID managerId) {
        UserManagerClient.ManagerSummary manager = users.get(managerId);
        TravelManagerClient.TravelStatistics statistics = travels.get(managerId);
        return new ManagerPage(manager.managerId(), manager.displayName(), manager.memberSince(),
                statistics.completedTravelCount(), statistics.totalTravelCount(), null, List.of(), 0);
    }

    record ManagerPage(UUID managerId, String displayName, Instant memberSince, long completedTravels,
                       long totalTravels, Double averageRating, List<Object> recentRatings, long reportCount) {}

    static final class ManagerNotFound extends RuntimeException {}
    static final class DependencyUnavailable extends RuntimeException {}
}
