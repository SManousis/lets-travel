package com.travelplan.engagement;

import com.travelplan.common.security.AccessToken;
import com.travelplan.common.security.Role;
import com.travelplan.engagement.dashboard.DashboardCalculator;
import com.travelplan.engagement.dashboard.DashboardCalculator.Money;
import com.travelplan.engagement.dashboard.DashboardStore;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Dashboards for every role, computed from engagement-service's own records on each request (see
 * docs/dashboards.md). Scope is enforced here, not only at the gateway: travelers see themselves, managers
 * their own travels (admins may name any manager), and only admins see platform-wide figures.
 */
@Service
class DashboardService {
    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);
    private final DashboardStore store;
    private final UserManagerClient users;
    private final PaymentInsightsClient payments;
    private final Clock clock;
    private final String platformCurrency;

    DashboardService(DashboardStore store, UserManagerClient users, PaymentInsightsClient payments, Clock clock,
                     @Value("${dashboards.platform-currency:EUR}") String platformCurrency) {
        this.store = store;
        this.users = users;
        this.payments = payments;
        this.clock = clock;
        this.platformCurrency = platformCurrency;
    }

    Traveler traveler(AccessToken caller) {
        DashboardCalculator.TravelerStatistics statistics = calculator().traveler(caller.userId());
        List<PaymentInsightsClient.Usage> usage;
        try {
            usage = payments.usage(caller.userId());
        } catch (PaymentInsightsClient.Unavailable unavailable) {
            log.warn("Payment usage unavailable for the traveler dashboard; showing none");
            usage = List.of();
        }
        return new Traveler(statistics, usage, clock.instant());
    }

    /** A manager always gets their own dashboard; an admin may ask for any manager's. */
    Manager manager(AccessToken caller, UUID requestedManager, int months) {
        UUID managerId = caller.role() == Role.ADMIN && requestedManager != null ? requestedManager : caller.userId();
        return new Manager(calculator().manager(managerId, months), clock.instant());
    }

    Admin admin(int months, int top) {
        DashboardCalculator.AdminDashboard dashboard = calculator().admin(months, top);
        Map<UUID, String> names = new HashMap<>();
        return new Admin(dashboard, dashboard.topManagers().stream().map(r -> name(names, r.managerId())).toList(),
                dashboard.topTravels().stream().map(t -> name(names, t.managerId())).toList(), clock.instant());
    }

    Ranked ranking() {
        Map<UUID, String> names = new HashMap<>();
        List<DashboardCalculator.ManagerRanking> ranking = calculator().ranking();
        return new Ranked(ranking, ranking.stream().map(r -> name(names, r.managerId())).toList(), clock.instant());
    }

    History travelHistory(UUID managerId) {
        Map<UUID, String> names = new HashMap<>();
        List<DashboardCalculator.TravelSummary> travels = calculator().travelHistory(managerId);
        return new History(travels, travels.stream().map(t -> name(names, t.managerId())).toList(), clock.instant());
    }

    /**
     * Compares the net income engagement-service derives from subscriptions (paid minus refunded) with the money
     * payment-service holds as SUCCEEDED payments. Cancelling a paid subscription moves its payment out of
     * SUCCEEDED, so the two are equal once in-flight refunds and payment outcomes have been delivered.
     */
    List<Reconciliation> reconcile() {
        Map<String, Long> net = new HashMap<>();
        for (Money money : calculator().netIncome()) net.put(money.currency(), money.amountMinor());
        Map<String, PaymentInsightsClient.Totals> held = new HashMap<>();
        for (PaymentInsightsClient.Totals totals : payments.totals()) held.put(totals.currency(), totals);
        java.util.TreeSet<String> currencies = new java.util.TreeSet<>(net.keySet());
        currencies.addAll(held.keySet());
        return currencies.stream().map(currency -> {
            long expected = net.getOrDefault(currency, 0L);
            PaymentInsightsClient.Totals totals = held.getOrDefault(currency, new PaymentInsightsClient.Totals(currency, 0, 0, 0));
            long difference = totals.succeededMinor() - expected;
            // A difference is normal for a moment while a refund request or a payment outcome is still on its way.
            return new Reconciliation(currency, expected, totals.succeededMinor(), totals.refundPendingMinor(), difference,
                    difference == 0 ? "MATCH" : "DIFFERENT");
        }).toList();
    }

    private String name(Map<UUID, String> cache, UUID managerId) {
        return cache.computeIfAbsent(managerId, id -> {
            try {
                return users.get(id).displayName();
            } catch (RuntimeException unavailable) {
                return "Manager " + id.toString().substring(0, 8);
            }
        });
    }

    private DashboardCalculator calculator() {
        if (!store.available()) throw new ManagerPageService.DependencyUnavailable();
        return new DashboardCalculator(store.facts(), LocalDate.now(clock.withZone(ZoneOffset.UTC)), platformCurrency);
    }

    record Traveler(DashboardCalculator.TravelerStatistics statistics, List<PaymentInsightsClient.Usage> paymentUsage, Instant asOf) {}

    record Manager(DashboardCalculator.ManagerDashboard dashboard, Instant asOf) {}

    record Admin(DashboardCalculator.AdminDashboard dashboard, List<String> topManagerNames, List<String> topTravelManagerNames,
                 Instant asOf) {}

    record Ranked(List<DashboardCalculator.ManagerRanking> ranking, List<String> names, Instant asOf) {}

    record History(List<DashboardCalculator.TravelSummary> travels, List<String> managerNames, Instant asOf) {}

    record Reconciliation(String currency, long subscriptionNetMinor, long paymentsSucceededMinor, long refundPendingMinor,
                          long difference, String status) {}
}
