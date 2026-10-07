package com.travelplan.engagement;

import com.travelplan.common.security.AccessToken;
import com.travelplan.common.security.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Report rules (docs/feedback-and-reports.md): no self-reports, the target must exist, one open
 * report per reporter and target, and at most {@value #DAILY_LIMIT} reports per reporter per 24 hours.
 * Report content is shown only to admins and to the reporter; everyone else sees the upheld count.
 */
@Service
class ReportService {
    static final int DAILY_LIMIT = 10;
    private final ReportStore store;
    private final UserSummaryClient users;
    private final UserManagerClient managers;
    private final TravelReservationClient travels;
    private final Clock clock;

    ReportService(ReportStore store, UserSummaryClient users, UserManagerClient managers,
                  TravelReservationClient travels, Clock clock) {
        this.store = store;
        this.users = users;
        this.managers = managers;
        this.travels = travels;
        this.clock = clock;
    }

    ReportStore.Report file(AccessToken caller, ReportStore.TargetType type, UUID targetId, ReportStore.Category category,
                            String description, UUID idempotencyKey) {
        store.requireAvailable();
        Optional<ReportStore.Report> earlier = store.byIdempotencyKey(caller.userId(), idempotencyKey);
        if (earlier.isPresent()) return replay(earlier.get(), type, targetId);
        if (targetId.equals(caller.userId())) throw selfReport();
        if (store.hasOpen(caller.userId(), type, targetId)) throw duplicate();
        Instant now = clock.instant();
        if (store.filedSince(caller.userId(), now.minus(Duration.ofHours(24))) >= DAILY_LIMIT)
            throw new SubscriptionService.Failure(429, "TOO_MANY_REPORTS",
                    "You can file at most " + DAILY_LIMIT + " reports per day");

        String targetName = targetName(caller, type, targetId);
        ReportStore.Report report = new ReportStore.Report(UUID.randomUUID(), caller.userId(), type, targetId, targetName,
                category, description.strip(), ReportStore.Status.OPEN, null, idempotencyKey, now, now);
        try {
            store.insert(report);
            return report;
        } catch (DuplicateKeyException race) {
            Optional<ReportStore.Report> winner = store.byIdempotencyKey(caller.userId(), idempotencyKey);
            if (winner.isPresent()) return replay(winner.get(), type, targetId);
            throw duplicate();
        }
    }

    ReportStore.Page mine(AccessToken caller, int page, int size) {
        return store.byReporter(caller.userId(), page, size);
    }

    ReportStore.Page queue(AccessToken caller, ReportStore.Status status, ReportStore.TargetType type, int page, int size) {
        requireAdmin(caller);
        return store.queue(status, type, page, size);
    }

    ReportStore.Report get(AccessToken caller, UUID reportId) {
        requireAdmin(caller);
        return store.byId(reportId).orElseThrow(ReportService::notFound);
    }

    ReportStore.Report moderate(AccessToken caller, UUID reportId, ReportStore.Status to, boolean assignToSelf, String note) {
        requireAdmin(caller);
        if (to == ReportStore.Status.OPEN)
            throw new SubscriptionService.Failure(400, "VALIDATION_ERROR", "status must be IN_REVIEW, RESOLVED or DISMISSED");
        String trimmed = note.strip();
        if (trimmed.length() < 3)
            throw new SubscriptionService.Failure(400, "VALIDATION_ERROR", "A note of at least 3 characters is required");
        ReportStore.Report current = store.byId(reportId).orElseThrow(ReportService::notFound);
        if (current.status().closed()) throw closed();
        return store.moderate(reportId, to, caller.userId(), assignToSelf, trimmed, clock.instant())
                .orElseThrow(ReportService::closed);
    }

    /** Checks that the target exists (asking its owning service) and returns its display name. */
    private String targetName(AccessToken caller, ReportStore.TargetType type, UUID targetId) {
        return switch (type) {
            case TRAVEL_MANAGER -> {
                try {
                    yield managers.get(targetId).displayName();
                } catch (ManagerPageService.ManagerNotFound missing) {
                    throw targetNotFound();
                }
            }
            case TRAVELER -> users.find(targetId).map(UserSummaryClient.UserSummary::displayName)
                    .orElseThrow(ReportService::targetNotFound);
            case TRAVEL -> {
                TravelReservationClient.TravelSnapshot travel;
                try {
                    travel = travels.summary(targetId);
                } catch (SubscriptionService.Failure missing) {
                    throw targetNotFound();
                }
                // A manager cannot report their own travel.
                if (travel.managerId().equals(caller.userId())) throw selfReport();
                yield travel.title();
            }
        };
    }

    private static void requireAdmin(AccessToken caller) {
        if (caller.role() != Role.ADMIN) throw notFound();
    }

    private static ReportStore.Report replay(ReportStore.Report earlier, ReportStore.TargetType type, UUID targetId) {
        if (earlier.targetType() != type || !earlier.targetId().equals(targetId))
            throw new SubscriptionService.Failure(409, "IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was used for a different request");
        return earlier;
    }

    private static SubscriptionService.Failure selfReport() {
        return new SubscriptionService.Failure(400, "SELF_REPORT", "You cannot report yourself");
    }

    private static SubscriptionService.Failure duplicate() {
        return new SubscriptionService.Failure(409, "DUPLICATE_REPORT", "You already have an open report on this target");
    }

    private static SubscriptionService.Failure closed() {
        return new SubscriptionService.Failure(409, "REPORT_CLOSED", "This report is already resolved or dismissed");
    }

    private static SubscriptionService.Failure notFound() {
        return new SubscriptionService.Failure(404, "NOT_FOUND", "Report not found");
    }

    private static SubscriptionService.Failure targetNotFound() {
        return new SubscriptionService.Failure(404, "NOT_FOUND", "Report target not found");
    }
}
