package com.travelplan.engagement.dashboard;

import com.travelplan.engagement.dashboard.DashboardFacts.FeedbackFact;
import com.travelplan.engagement.dashboard.DashboardFacts.ReportFact;
import com.travelplan.engagement.dashboard.DashboardFacts.SubscriptionFact;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Computes every dashboard figure from {@link DashboardFacts}. Pure and deterministic; the rules are listed in
 * docs/dashboards.md and each is small enough to check by hand:
 * <ul>
 *   <li><b>Income</b>: a subscription's price counts in the UTC month it was paid and is subtracted in the month it
 *       was refunded (a refund is always the full price). Each currency is kept separate; nothing is converted.</li>
 *   <li><b>Travelers</b>: subscriptions that are CONFIRMED or COMPLETED.</li>
 *   <li><b>Cancellations</b>: paid subscriptions that were later cancelled (refunded). The cancellation rate is
 *       cancellations / paid subscriptions, 0 when nothing was paid.</li>
 *   <li><b>Trips</b>: travels with at least one traveler; upcoming when they start after today.</li>
 *   <li><b>Ratings</b>: the average of feedback ratings rounded to one decimal, with their count.</li>
 * </ul>
 */
public final class DashboardCalculator {
    /** Weights of the manager performance score; see {@link #ranking}. */
    static final double RATING_WEIGHT = 0.40, INCOME_WEIGHT = 0.30, COMPLETED_WEIGHT = 0.20, RETENTION_WEIGHT = 0.10,
            REPORT_PENALTY = 0.15;
    static final int RANKING_MONTHS = 12;

    private final DashboardFacts facts;
    private final LocalDate today;
    private final String platformCurrency;

    public DashboardCalculator(DashboardFacts facts, LocalDate today, String platformCurrency) {
        this.facts = facts;
        this.today = today;
        this.platformCurrency = platformCurrency;
    }

    // --- Traveler ---------------------------------------------------------------------------------------------

    public TravelerStatistics traveler(UUID travelerId) {
        List<SubscriptionFact> own = subscriptions(s -> s.travelerId().equals(travelerId));
        return new TravelerStatistics(
                (int) own.stream().filter(s -> "COMPLETED".equals(s.status())).count(),
                (int) own.stream().filter(s -> "CONFIRMED".equals(s.status()) && s.startDate().isAfter(today)).count(),
                (int) own.stream().filter(s -> "CANCELLED_BY_TRAVELER".equals(s.status())).count(),
                facts.reports().stream().filter(r -> r.reporterId().equals(travelerId)).count(),
                facts.reports().stream().filter(r -> r.targetId().equals(travelerId) && r.upheld()
                        && !"TRAVEL".equals(r.targetType())).count(),
                (int) facts.feedback().stream().filter(f -> f.travelerId().equals(travelerId)).count());
    }

    // --- Travel manager ---------------------------------------------------------------------------------------

    public ManagerDashboard manager(UUID managerId, int months) {
        List<SubscriptionFact> own = subscriptions(s -> s.managerId().equals(managerId));
        List<TravelSummary> travels = travelSummaries(own).stream()
                .sorted(Comparator.comparing(TravelSummary::startDate).reversed().thenComparing(TravelSummary::travelId))
                .toList();
        List<TravelSummary> withTravelers = travels.stream().filter(t -> t.travelers() > 0).toList();
        return new ManagerDashboard(managerId, incomeByMonth(own, months), withTravelers.size(),
                (int) withTravelers.stream().filter(t -> t.startDate().isAfter(today)).count(),
                (int) own.stream().filter(SubscriptionFact::participating).count(), cancellationRate(own),
                rating(facts.feedback().stream().filter(f -> f.managerId().equals(managerId)).toList()), travels);
    }

    // --- Admin ------------------------------------------------------------------------------------------------

    public AdminDashboard admin(int months, int top) {
        YearMonth first = YearMonth.from(today).minusMonths(months - 1L);
        Predicate<LocalDate> inWindow = date -> !YearMonth.from(date).isBefore(first) && !date.isAfter(today);
        List<SubscriptionFact> all = facts.subscriptions();
        Set<UUID> organized = all.stream().filter(s -> s.participating() && inWindow.test(s.startDate()))
                .map(SubscriptionFact::travelId).collect(Collectors.toSet());
        Set<UUID> travelers = all.stream().filter(s -> s.participating() && inWindow.test(s.startDate()))
                .map(SubscriptionFact::travelerId).collect(Collectors.toSet());
        Instant windowStart = first.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        List<FeedbackFact> recentFeedback = facts.feedback().stream().filter(f -> !f.createdAt().isBefore(windowStart)).toList();
        List<TravelSummary> started = travelHistory(null);
        List<TravelSummary> topTravels = started.stream()
                .sorted(Comparator.comparing((TravelSummary t) -> t.rating().average(), Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(TravelSummary::travelers, Comparator.reverseOrder())
                        .thenComparing(TravelSummary::startDate, Comparator.reverseOrder())
                        .thenComparing(TravelSummary::travelId))
                .limit(top).toList();
        return new AdminDashboard(incomeByMonth(all, months), organized.size(), travelers.size(), rating(recentFeedback),
                facts.reports().stream().filter(ReportFact::open).count(),
                ranking().stream().limit(top).toList(), topTravels);
    }

    /** Every travel that has started (start date today or earlier), most recent first; optionally one manager's. */
    public List<TravelSummary> travelHistory(UUID managerId) {
        return travelSummaries(subscriptions(s -> managerId == null || s.managerId().equals(managerId))).stream()
                .filter(t -> !t.startDate().isAfter(today))
                .sorted(Comparator.comparing(TravelSummary::startDate).reversed().thenComparing(TravelSummary::travelId))
                .toList();
    }

    /**
     * Managers ranked by performance over the last {@value #RANKING_MONTHS} calendar months:
     * <pre>
     * score = 0.40 x rating + 0.30 x income + 0.20 x completed travels + 0.10 x (1 - cancellation rate)
     *         - 0.15 x upheld reports
     * </pre>
     * Rating, income (platform currency, net of refunds), completed travels and upheld reports are each divided by
     * the highest value among the ranked managers, giving 0..1; the cancellation rate is already 0..1. A manager is
     * ranked when they had a paid subscription, a rating or an upheld report in the period. Ties break on completed
     * travels, then manager ID.
     */
    public List<ManagerRanking> ranking() {
        YearMonth first = YearMonth.from(today).minusMonths(RANKING_MONTHS - 1L);
        Instant from = first.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Predicate<Instant> recent = at -> at != null && !at.isBefore(from);
        Map<UUID, Raw> raw = new LinkedHashMap<>();
        for (SubscriptionFact s : facts.subscriptions()) {
            if (!recent.test(s.paidAt()) && !recent.test(s.refundedAt())) continue;
            Raw r = raw.computeIfAbsent(s.managerId(), Raw::new);
            if (recent.test(s.paidAt())) {
                r.paid++;
                if (s.currency().equals(platformCurrency)) r.income += s.amountMinor();
                if (s.refunded()) r.cancelled++;
            }
            if (recent.test(s.refundedAt()) && s.currency().equals(platformCurrency)) r.income -= s.amountMinor();
        }
        for (SubscriptionFact s : facts.subscriptions()) {
            if ("COMPLETED".equals(s.status()) && !s.endDate().isAfter(today) && !YearMonth.from(s.endDate()).isBefore(first)) {
                raw.computeIfAbsent(s.managerId(), Raw::new).completedTravels.add(s.travelId());
            }
        }
        for (FeedbackFact f : facts.feedback()) {
            if (recent.test(f.createdAt())) raw.computeIfAbsent(f.managerId(), Raw::new).ratings.add(f);
        }
        for (ReportFact r : facts.reports()) {
            if ("TRAVEL_MANAGER".equals(r.targetType()) && r.upheld() && recent.test(r.createdAt())) {
                raw.computeIfAbsent(r.targetId(), Raw::new).upheldReports++;
            }
        }
        double maxRating = raw.values().stream().mapToDouble(Raw::averageRating).max().orElse(0);
        double maxIncome = raw.values().stream().mapToDouble(r -> Math.max(0, r.income)).max().orElse(0);
        double maxCompleted = raw.values().stream().mapToDouble(r -> r.completedTravels.size()).max().orElse(0);
        double maxReports = raw.values().stream().mapToDouble(r -> r.upheldReports).max().orElse(0);

        List<ManagerRanking> ranked = new ArrayList<>();
        for (Raw r : raw.values()) {
            Components components = new Components(ratio(r.averageRating(), maxRating), ratio(Math.max(0, r.income), maxIncome),
                    ratio(r.completedTravels.size(), maxCompleted), r.paid == 0 ? 0 : (double) r.cancelled / r.paid,
                    ratio(r.upheldReports, maxReports));
            double score = RATING_WEIGHT * components.rating() + INCOME_WEIGHT * components.income()
                    + COMPLETED_WEIGHT * components.completedTravels() + RETENTION_WEIGHT * (1 - components.cancellationRate())
                    - REPORT_PENALTY * components.upheldReports();
            ranked.add(new ManagerRanking(0, r.managerId, round(score), components, r.completedTravels.size(),
                    new Money(r.income, platformCurrency), rating(r.ratings), r.upheldReports));
        }
        ranked.sort(Comparator.comparingDouble(ManagerRanking::score).reversed()
                .thenComparing(ManagerRanking::completedTravels, Comparator.reverseOrder())
                .thenComparing(ManagerRanking::managerId));
        List<ManagerRanking> numbered = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) numbered.add(ranked.get(i).withRank(i + 1));
        return numbered;
    }

    /** Net income per currency over all time: what payment-service should hold as succeeded payments. */
    public List<Money> netIncome() {
        return net(facts.subscriptions());
    }

    // --- Shared rules -----------------------------------------------------------------------------------------

    private List<MonthlyIncome> incomeByMonth(List<SubscriptionFact> subscriptions, int months) {
        YearMonth last = YearMonth.from(today);
        Map<YearMonth, Map<String, Long>> totals = new TreeMap<>();
        for (int i = months - 1; i >= 0; i--) totals.put(last.minusMonths(i), new TreeMap<>());
        for (SubscriptionFact s : subscriptions) {
            add(totals, s.paidAt(), s.currency(), s.amountMinor());
            add(totals, s.refundedAt(), s.currency(), -s.amountMinor());
        }
        return totals.entrySet().stream().map(entry -> new MonthlyIncome(entry.getKey(), entry.getValue().entrySet().stream()
                .filter(amount -> amount.getValue() != 0).map(amount -> new Money(amount.getValue(), amount.getKey())).toList()))
                .toList();
    }

    private static void add(Map<YearMonth, Map<String, Long>> totals, Instant at, String currency, long amount) {
        if (at == null) return;
        Map<String, Long> month = totals.get(YearMonth.from(at.atZone(ZoneOffset.UTC)));
        if (month != null) month.merge(currency, amount, Long::sum);
    }

    private List<TravelSummary> travelSummaries(List<SubscriptionFact> subscriptions) {
        Map<UUID, List<SubscriptionFact>> byTravel = new LinkedHashMap<>();
        for (SubscriptionFact s : subscriptions) byTravel.computeIfAbsent(s.travelId(), id -> new ArrayList<>()).add(s);
        List<TravelSummary> summaries = new ArrayList<>();
        for (List<SubscriptionFact> travel : byTravel.values()) {
            SubscriptionFact any = travel.getFirst();
            summaries.add(new TravelSummary(any.travelId(), any.travelTitle(), any.managerId(), any.startDate(), any.endDate(),
                    (int) travel.stream().filter(SubscriptionFact::participating).count(),
                    (int) travel.stream().filter(s -> s.paid() && s.refunded()).count(), net(travel),
                    rating(facts.feedback().stream().filter(f -> f.travelId().equals(any.travelId())).toList())));
        }
        return summaries;
    }

    private static List<Money> net(List<SubscriptionFact> subscriptions) {
        Map<String, Long> totals = new TreeMap<>();
        for (SubscriptionFact s : subscriptions) {
            if (s.paid()) totals.merge(s.currency(), s.amountMinor(), Long::sum);
            if (s.refunded()) totals.merge(s.currency(), -s.amountMinor(), Long::sum);
        }
        return totals.entrySet().stream().filter(entry -> entry.getValue() != 0)
                .map(entry -> new Money(entry.getValue(), entry.getKey())).toList();
    }

    private static double cancellationRate(List<SubscriptionFact> subscriptions) {
        long paid = subscriptions.stream().filter(SubscriptionFact::paid).count();
        long cancelled = subscriptions.stream().filter(s -> s.paid() && s.refunded()).count();
        return paid == 0 ? 0 : round((double) cancelled / paid);
    }

    private static RatingSummary rating(List<FeedbackFact> feedback) {
        if (feedback.isEmpty()) return new RatingSummary(null, 0);
        double average = feedback.stream().mapToInt(FeedbackFact::rating).average().orElse(0);
        return new RatingSummary(Math.round(average * 10) / 10.0, feedback.size());
    }

    private List<SubscriptionFact> subscriptions(Predicate<SubscriptionFact> filter) {
        return facts.subscriptions().stream().filter(filter).toList();
    }

    private static double ratio(double value, double max) {
        return max <= 0 ? 0 : value / max;
    }

    private static double round(double value) {
        return Math.round(value * 10_000) / 10_000.0;
    }

    private static final class Raw {
        final UUID managerId;
        long paid;
        long cancelled;
        long income;
        long upheldReports;
        final Set<UUID> completedTravels = new HashSet<>();
        final List<FeedbackFact> ratings = new ArrayList<>();

        Raw(UUID managerId) {
            this.managerId = managerId;
        }

        double averageRating() {
            return ratings.stream().mapToInt(FeedbackFact::rating).average().orElse(0);
        }
    }

    // --- Results ----------------------------------------------------------------------------------------------

    public record Money(long amountMinor, String currency) {}

    public record MonthlyIncome(YearMonth month, List<Money> amounts) {}

    public record RatingSummary(Double average, long count) {}

    public record TravelSummary(UUID travelId, String title, UUID managerId, LocalDate startDate, LocalDate endDate,
                                int travelers, int cancellations, List<Money> income, RatingSummary rating) {}

    public record TravelerStatistics(int completedTrips, int upcomingTrips, int cancellations, long reportsFiled,
                                     long upheldReportsAgainstMe, int feedbackGiven) {}

    public record ManagerDashboard(UUID managerId, List<MonthlyIncome> incomeByMonth, int totalTrips, int upcomingTrips,
                                   int totalTravelers, double cancellationRate, RatingSummary rating, List<TravelSummary> travels) {}

    /** The normalized inputs of the score, so a rank can be explained line by line. */
    public record Components(double rating, double income, double completedTravels, double cancellationRate, double upheldReports) {}

    public record ManagerRanking(int rank, UUID managerId, double score, Components components, int completedTravels,
                                 Money income, RatingSummary rating, long upheldReports) {
        ManagerRanking withRank(int value) {
            return new ManagerRanking(value, managerId, score, components, completedTravels, income, rating, upheldReports);
        }
    }

    public record AdminDashboard(List<MonthlyIncome> incomeByMonth, int organizedTravels, int activeTravelers,
                                 RatingSummary averageRating, long openReports, List<ManagerRanking> topManagers,
                                 List<TravelSummary> topTravels) {}
}
