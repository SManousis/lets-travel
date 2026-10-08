package com.travelplan.engagement;

import com.travelplan.common.security.AccessToken;
import com.travelplan.engagement.dashboard.DashboardCalculator;
import com.travelplan.engagement.dashboard.DashboardCalculator.Money;
import com.travelplan.engagement.dashboard.DashboardCalculator.RatingSummary;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Dashboards for every role (docs/api/dashboards.yaml); the rules behind each figure are in docs/dashboards.md. */
@RestController
class DashboardController {
    private final DashboardService dashboards;

    DashboardController(DashboardService dashboards) {
        this.dashboards = dashboards;
    }

    @GetMapping("/api/dashboards/me")
    TravelerView me(@AuthenticationPrincipal AccessToken caller) {
        DashboardService.Traveler result = dashboards.traveler(caller);
        DashboardCalculator.TravelerStatistics s = result.statistics();
        return new TravelerView(s.completedTrips(), s.upcomingTrips(), s.cancellations(), s.reportsFiled(),
                s.upheldReportsAgainstMe(), s.feedbackGiven(), result.paymentUsage().stream()
                .map(usage -> new UsageView(usage.provider(), usage.payments())).toList(), result.asOf());
    }

    @GetMapping("/api/dashboards/manager")
    ManagerView manager(@AuthenticationPrincipal AccessToken caller, @RequestParam(required = false) UUID managerId,
                        @RequestParam(defaultValue = "6") int months) {
        checkMonths(months);
        DashboardService.Manager result = dashboards.manager(caller, managerId, months);
        DashboardCalculator.ManagerDashboard d = result.dashboard();
        return new ManagerView(d.managerId(), income(d.incomeByMonth()), d.totalTrips(), d.upcomingTrips(), d.totalTravelers(),
                d.cancellationRate(), d.rating(), d.travels().stream().map(t -> travel(t, null)).toList(), result.asOf());
    }

    @GetMapping("/api/dashboards/admin")
    AdminView admin(@RequestParam(defaultValue = "6") int months, @RequestParam(defaultValue = "5") int top) {
        checkMonths(months);
        if (top < 1 || top > 20) throw new IllegalArgumentException("top must be between 1 and 20");
        DashboardService.Admin result = dashboards.admin(months, top);
        DashboardCalculator.AdminDashboard d = result.dashboard();
        List<RankingView> managers = new ArrayList<>();
        for (int i = 0; i < d.topManagers().size(); i++) managers.add(ranking(d.topManagers().get(i), result.topManagerNames().get(i)));
        List<TravelView> travels = new ArrayList<>();
        for (int i = 0; i < d.topTravels().size(); i++) travels.add(travel(d.topTravels().get(i), result.topTravelManagerNames().get(i)));
        return new AdminView(income(d.incomeByMonth()), d.organizedTravels(), d.activeTravelers(), d.averageRating(),
                d.openReports(), managers, travels, result.asOf());
    }

    @GetMapping("/api/dashboards/admin/managers")
    PageView<RankingView> rankManagers(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        SubscriptionController.checkPage(page, size);
        DashboardService.Ranked result = dashboards.ranking();
        List<RankingView> all = new ArrayList<>();
        for (int i = 0; i < result.ranking().size(); i++) all.add(ranking(result.ranking().get(i), result.names().get(i)));
        return PageView.of(all, page, size, result.asOf());
    }

    @GetMapping("/api/dashboards/admin/travels")
    PageView<TravelView> travelHistory(@RequestParam(required = false) UUID managerId, @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        SubscriptionController.checkPage(page, size);
        DashboardService.History result = dashboards.travelHistory(managerId);
        List<TravelView> all = new ArrayList<>();
        for (int i = 0; i < result.travels().size(); i++) all.add(travel(result.travels().get(i), result.managerNames().get(i)));
        return PageView.of(all, page, size, result.asOf());
    }

    /** Whether dashboard income agrees with the payments payment-service holds, per currency. */
    @GetMapping("/api/dashboards/admin/reconciliation")
    List<DashboardService.Reconciliation> reconciliation() {
        return dashboards.reconcile();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Error> invalid(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(new Error("VALIDATION_ERROR", failure.getMessage()));
    }

    @ExceptionHandler({ManagerPageService.DependencyUnavailable.class, PaymentInsightsClient.Unavailable.class})
    ResponseEntity<Error> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new Error("DEPENDENCY_UNAVAILABLE", "Dashboard data is temporarily unavailable"));
    }

    private static void checkMonths(int months) {
        if (months < 1 || months > 24) throw new IllegalArgumentException("months must be between 1 and 24");
    }

    private static List<MonthView> income(List<DashboardCalculator.MonthlyIncome> months) {
        return months.stream().map(month -> new MonthView(month.month().toString(), month.amounts())).toList();
    }

    private static TravelView travel(DashboardCalculator.TravelSummary t, String managerName) {
        return new TravelView(t.travelId(), t.title(), t.managerId(), managerName, t.startDate(), t.endDate(), t.travelers(),
                t.cancellations(), t.income(), t.rating());
    }

    private static RankingView ranking(DashboardCalculator.ManagerRanking r, String name) {
        return new RankingView(r.rank(), r.managerId(), name, r.score(), r.components(), r.completedTravels(), List.of(r.income()),
                r.rating(), r.upheldReports());
    }

    record UsageView(String provider, long payments) {}

    record TravelerView(int completedTrips, int upcomingTrips, int cancellations, long reportsFiled, long upheldReportsAgainstMe,
                        int feedbackGiven, List<UsageView> preferredPaymentMethods, Instant asOf) {}

    record MonthView(String month, List<Money> amounts) {}

    record TravelView(UUID travelId, String title, UUID managerId, String managerDisplayName, LocalDate startDate,
                      LocalDate endDate, int travelers, int cancellations, List<Money> income, RatingSummary rating) {}

    record ManagerView(UUID managerId, List<MonthView> incomeByMonth, int totalTrips, int upcomingTrips, int totalTravelers,
                       double cancellationRate, RatingSummary rating, List<TravelView> travels, Instant asOf) {}

    record RankingView(int rank, UUID managerId, String displayName, double score, DashboardCalculator.Components components,
                       int organizedTravels, List<Money> income, RatingSummary rating, long upheldReports) {}

    record AdminView(List<MonthView> incomeByMonth, int organizedTravels, int activeTravelers, RatingSummary averageRating,
                     long openReports, List<RankingView> topManagers, List<TravelView> topTravels, Instant asOf) {}

    record PageView<T>(List<T> items, int page, int size, long totalElements, int totalPages, Instant asOf) {
        static <T> PageView<T> of(List<T> all, int page, int size, Instant asOf) {
            int from = Math.min(page * size, all.size());
            return new PageView<>(all.subList(from, Math.min(from + size, all.size())), page, size, all.size(),
                    (all.size() + size - 1) / size, asOf);
        }
    }

    record Error(String code, String message, List<Object> fieldErrors, String traceId) {
        Error(String code, String message) {
            this(code, message, List.of(), "");
        }
    }
}
