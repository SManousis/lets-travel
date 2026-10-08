package com.travelplan.engagement.dashboard;

import com.travelplan.engagement.dashboard.DashboardCalculator.AdminDashboard;
import com.travelplan.engagement.dashboard.DashboardCalculator.ManagerDashboard;
import com.travelplan.engagement.dashboard.DashboardCalculator.ManagerRanking;
import com.travelplan.engagement.dashboard.DashboardCalculator.Money;
import com.travelplan.engagement.dashboard.DashboardCalculator.MonthlyIncome;
import com.travelplan.engagement.dashboard.DashboardCalculator.RatingSummary;
import com.travelplan.engagement.dashboard.DashboardCalculator.TravelSummary;
import com.travelplan.engagement.dashboard.DashboardCalculator.TravelerStatistics;
import com.travelplan.engagement.dashboard.DashboardFacts.FeedbackFact;
import com.travelplan.engagement.dashboard.DashboardFacts.ReportFact;
import com.travelplan.engagement.dashboard.DashboardFacts.SubscriptionFact;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One small fixture whose figures can be checked by hand (today is 2026-10-15):
 *
 * <pre>
 * Manager Ana: Rome  (A1) 2026-09-01..05, EUR 500.00   Paris (A2) 2026-11-10..14, EUR 300.00
 * Manager Bo:  Crete (B1) 2026-08-10..15, EUR 400.00
 *
 * #  traveler travel status                 paid        refunded
 * 1  t1       A1     COMPLETED              2026-08-05
 * 2  t2       A1     COMPLETED              2026-08-20
 * 3  t3       A1     CANCELLED_BY_TRAVELER  2026-08-21  2026-08-25
 * 4  t1       A2     CONFIRMED              2026-10-01
 * 5  t4       A2     PENDING_PAYMENT        -
 * 6  t2       B1     COMPLETED              2026-07-30
 * 7  t5       B1     CANCELLED_BY_MANAGER   2026-08-01  2026-09-02
 *
 * Feedback: t1 on A1 = 5, t2 on A1 = 4, t2 on B1 = 2
 * Reports:  t1 -> Bo (manager) RESOLVED, t1 -> Ana (manager) DISMISSED, t2 -> t3 (traveler) OPEN
 * </pre>
 */
class DashboardCalculatorTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private final UUID ana = id("ana"), bo = id("bo");
    private final UUID a1 = id("A1"), a2 = id("A2"), b1 = id("B1");
    private final UUID t1 = id("t1"), t2 = id("t2"), t3 = id("t3"), t4 = id("t4"), t5 = id("t5");

    private final DashboardCalculator calculator = new DashboardCalculator(new DashboardFacts(
            List.of(sub(t1, a1, ana, "COMPLETED", "2026-08-05", null),
                    sub(t2, a1, ana, "COMPLETED", "2026-08-20", null),
                    sub(t3, a1, ana, "CANCELLED_BY_TRAVELER", "2026-08-21", "2026-08-25"),
                    sub(t1, a2, ana, "CONFIRMED", "2026-10-01", null),
                    sub(t4, a2, ana, "PENDING_PAYMENT", null, null),
                    sub(t2, b1, bo, "COMPLETED", "2026-07-30", null),
                    sub(t5, b1, bo, "CANCELLED_BY_MANAGER", "2026-08-01", "2026-09-02")),
            List.of(new FeedbackFact(a1, ana, t1, 5, at("2026-09-06")), new FeedbackFact(a1, ana, t2, 4, at("2026-09-07")),
                    new FeedbackFact(b1, bo, t2, 2, at("2026-08-16"))),
            List.of(new ReportFact(t1, "TRAVEL_MANAGER", bo, "RESOLVED", at("2026-09-10")),
                    new ReportFact(t1, "TRAVEL_MANAGER", ana, "DISMISSED", at("2026-09-11")),
                    new ReportFact(t2, "TRAVELER", t3, "OPEN", at("2026-09-12")))),
            TODAY, "EUR");

    @Test void managerDashboardForAna() {
        ManagerDashboard dashboard = calculator.manager(ana, 3);

        // Aug: 500 + 500 + 500 (#1-#3) - 500 (#3 refunded) = 1000.00; Sep: nothing; Oct: 300.00 (#4).
        assertThat(dashboard.incomeByMonth()).containsExactly(
                new MonthlyIncome(YearMonth.of(2026, 8), List.of(eur(100000))),
                new MonthlyIncome(YearMonth.of(2026, 9), List.of()),
                new MonthlyIncome(YearMonth.of(2026, 10), List.of(eur(30000))));
        assertThat(dashboard.totalTrips()).isEqualTo(2);          // A1 and A2 have travelers
        assertThat(dashboard.upcomingTrips()).isEqualTo(1);       // A2 starts in November
        assertThat(dashboard.totalTravelers()).isEqualTo(3);      // #1, #2, #4 (#5 is unpaid)
        assertThat(dashboard.cancellationRate()).isEqualTo(0.25); // 1 refunded of 4 paid
        assertThat(dashboard.rating()).isEqualTo(new RatingSummary(4.5, 2));
        assertThat(dashboard.travels()).extracting(TravelSummary::travelId).containsExactly(a2, a1);
        TravelSummary rome = dashboard.travels().get(1);
        assertThat(rome.travelers()).isEqualTo(2);
        assertThat(rome.cancellations()).isEqualTo(1);
        assertThat(rome.income()).containsExactly(eur(100000)); // 1500.00 paid - 500.00 refunded
    }

    @Test void travelerStatistics() {
        assertThat(calculator.traveler(t1)).isEqualTo(new TravelerStatistics(1, 1, 0, 2, 0, 1));
        assertThat(calculator.traveler(t3)).isEqualTo(new TravelerStatistics(0, 0, 1, 0, 0, 0));
        assertThat(calculator.traveler(t5)).as("cancelled by the manager, not by the traveler")
                .isEqualTo(new TravelerStatistics(0, 0, 0, 0, 0, 0));
    }

    @Test void upheldReportsAgainstATravelerCount() {
        DashboardCalculator withUpheld = new DashboardCalculator(new DashboardFacts(List.of(), List.of(),
                List.of(new ReportFact(t1, "TRAVELER", t3, "RESOLVED", at("2026-09-12")),
                        new ReportFact(t1, "TRAVELER", t3, "DISMISSED", at("2026-09-13")))), TODAY, "EUR");
        assertThat(withUpheld.traveler(t3).upheldReportsAgainstMe()).isEqualTo(1);
    }

    @Test void managerRankingIsExplainable() {
        List<ManagerRanking> ranking = calculator.ranking();

        assertThat(ranking).extracting(ManagerRanking::managerId).containsExactly(ana, bo);
        ManagerRanking first = ranking.get(0);
        // Ana: rating 4.5 (max), income 1300.00 (max), 1 completed travel (max), 1 of 4 cancelled, 0 reports.
        // 0.40 x 1 + 0.30 x 1 + 0.20 x 1 + 0.10 x 0.75 - 0.15 x 0 = 0.975
        assertThat(first.rank()).isEqualTo(1);
        assertThat(first.score()).isEqualTo(0.975);
        assertThat(first.income()).isEqualTo(eur(130000));
        assertThat(first.components().cancellationRate()).isEqualTo(0.25);
        ManagerRanking second = ranking.get(1);
        // Bo: rating 2.0/4.5, income 400.00/1300.00, 1 completed travel, 1 of 2 cancelled, 1 upheld report (max).
        // 0.40 x 0.4444 + 0.30 x 0.3077 + 0.20 x 1 + 0.10 x 0.5 - 0.15 x 1 = 0.3701
        assertThat(second.rank()).isEqualTo(2);
        assertThat(second.score()).isEqualTo(0.3701);
        assertThat(second.upheldReports()).isEqualTo(1);
        assertThat(second.components().upheldReports()).isEqualTo(1.0);
    }

    @Test void adminOverview() {
        AdminDashboard admin = calculator.admin(3, 5);

        // Aug: Ana 1000.00 + Bo 400.00 (#7 paid 08-01; #6 was paid in July); Sep: -400.00 (#7 refunded); Oct: 300.00.
        assertThat(admin.incomeByMonth()).containsExactly(
                new MonthlyIncome(YearMonth.of(2026, 8), List.of(eur(140000))),
                new MonthlyIncome(YearMonth.of(2026, 9), List.of(eur(-40000))),
                new MonthlyIncome(YearMonth.of(2026, 10), List.of(eur(30000))));
        assertThat(admin.organizedTravels()).isEqualTo(2);   // A1 and B1 started Aug-Oct; A2 has not started
        assertThat(admin.activeTravelers()).isEqualTo(2);    // t1, t2
        assertThat(admin.averageRating()).isEqualTo(new RatingSummary(3.7, 3)); // (5 + 4 + 2) / 3
        assertThat(admin.openReports()).isEqualTo(1);
        assertThat(admin.topManagers()).extracting(ManagerRanking::managerId).containsExactly(ana, bo);
        assertThat(admin.topTravels()).extracting(TravelSummary::travelId).containsExactly(a1, b1);
    }

    @Test void travelHistoryListsStartedTravelsMostRecentFirst() {
        assertThat(calculator.travelHistory(null)).extracting(TravelSummary::travelId).containsExactly(a1, b1);
        assertThat(calculator.travelHistory(bo)).extracting(TravelSummary::travelId).containsExactly(b1);
    }

    @Test void netIncomeMatchesWhatPaymentServiceShouldHold() {
        // Ana 1500.00 + 300.00 - 500.00, Bo 800.00 - 400.00
        assertThat(calculator.netIncome()).containsExactly(eur(170000));
    }

    @Test void currenciesAreNeverMixedOrConverted() {
        DashboardCalculator mixed = new DashboardCalculator(new DashboardFacts(List.of(
                new SubscriptionFact(UUID.randomUUID(), a1, ana, "Rome", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 5),
                        t1, "COMPLETED", 50000, "EUR", at("2026-08-05"), null),
                new SubscriptionFact(UUID.randomUUID(), a1, ana, "Rome", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 5),
                        t2, "COMPLETED", 45000, "USD", at("2026-08-06"), null)), List.of(), List.of()), TODAY, "EUR");
        assertThat(mixed.netIncome()).containsExactly(eur(50000), new Money(45000, "USD"));
        assertThat(mixed.ranking().getFirst().income()).as("ranking uses the platform currency only").isEqualTo(eur(50000));
    }

    @Test void nothingPaidMeansZeroRatesNotErrors() {
        DashboardCalculator empty = new DashboardCalculator(new DashboardFacts(List.of(), List.of(), List.of()), TODAY, "EUR");
        assertThat(empty.manager(ana, 6).cancellationRate()).isZero();
        assertThat(empty.manager(ana, 6).incomeByMonth()).hasSize(6).allSatisfy(month -> assertThat(month.amounts()).isEmpty());
        assertThat(empty.ranking()).isEmpty();
        assertThat(empty.admin(6, 5).averageRating()).isEqualTo(new RatingSummary(null, 0));
    }

    private SubscriptionFact sub(UUID traveler, UUID travel, UUID manager, String status, String paid, String refunded) {
        String title = travel.equals(a1) ? "Rome" : travel.equals(a2) ? "Paris" : "Crete";
        LocalDate start = travel.equals(a1) ? LocalDate.of(2026, 9, 1) : travel.equals(a2) ? LocalDate.of(2026, 11, 10)
                : LocalDate.of(2026, 8, 10);
        long price = travel.equals(a1) ? 50000 : travel.equals(a2) ? 30000 : 40000;
        return new SubscriptionFact(UUID.randomUUID(), travel, manager, title, start, start.plusDays(4), traveler, status,
                price, "EUR", paid == null ? null : at(paid), refunded == null ? null : at(refunded));
    }

    private static Money eur(long amountMinor) {
        return new Money(amountMinor, "EUR");
    }

    private static Instant at(String date) {
        return Instant.parse(date + "T12:00:00Z");
    }

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes());
    }
}
