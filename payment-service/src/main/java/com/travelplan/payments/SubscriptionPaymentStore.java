package com.travelplan.payments;

import com.travelplan.payments.SubscriptionPayment.State;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Subscription payments in PostgreSQL ({@code infra} profile) or, like {@link PaymentStore}, in
 * memory for the infrastructure-free default profile. Both enforce the same two unique rules:
 * one payment per subscription and one payment per (user, idempotency key).
 */
@Repository
class SubscriptionPaymentStore {
    private static final String COLUMNS = "payment_id, subscription_id, user_id, amount_minor, currency, provider, method_id, "
            + "test_scenario, method_display, idempotency_key, request_hash, state, provider_reference, failure_code, "
            + "outcome_delivered, refund_reason, refund_reference, attempts, created_at, updated_at";
    private static final RowMapper<SubscriptionPayment> ROW = SubscriptionPaymentStore::map;
    private final JdbcTemplate jdbc;
    private final Map<UUID, SubscriptionPayment> memory = new ConcurrentHashMap<>();

    @Autowired
    SubscriptionPaymentStore(ObjectProvider<JdbcTemplate> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    /** Test constructor: a JdbcTemplate, or null for the in-memory store. */
    SubscriptionPaymentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Throws DuplicateKeyException when the subscription already has a payment or the key was used. */
    synchronized void insert(SubscriptionPayment payment) {
        if (jdbc != null) {
            jdbc.update("INSERT INTO subscription_payment (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    payment.paymentId(), payment.subscriptionId(), payment.userId(), payment.amountMinor(),
                    payment.currency(), payment.provider(), payment.methodId(), payment.testScenario(),
                    payment.methodDisplay(), payment.idempotencyKey(), payment.requestHash(), payment.state().name(),
                    payment.providerReference(), payment.failureCode(), payment.outcomeDelivered(), payment.refundReason(),
                    payment.refundReference(), payment.attempts(), time(payment.createdAt()), time(payment.updatedAt()));
            return;
        }
        boolean duplicate = memory.values().stream().anyMatch(existing ->
                existing.subscriptionId().equals(payment.subscriptionId())
                        || existing.userId().equals(payment.userId()) && existing.idempotencyKey().equals(payment.idempotencyKey()));
        if (duplicate) throw new DuplicateKeyException("subscription_payment");
        memory.put(payment.paymentId(), payment);
    }

    /**
     * Optimistic update: applied only if the row still has {@code expected}'s state and attempt
     * count, so two workers (request, webhook, reconciliation) can never both move the same payment.
     */
    synchronized boolean update(SubscriptionPayment next, SubscriptionPayment expected) {
        if (jdbc != null) {
            return jdbc.update("UPDATE subscription_payment SET state = ?, provider_reference = ?, failure_code = ?, "
                            + "outcome_delivered = ?, refund_reason = ?, refund_reference = ?, attempts = ?, updated_at = ? "
                            + "WHERE payment_id = ? AND state = ? AND attempts = ?",
                    next.state().name(), next.providerReference(), next.failureCode(), next.outcomeDelivered(),
                    next.refundReason(), next.refundReference(), next.attempts(), time(next.updatedAt()),
                    expected.paymentId(), expected.state().name(), expected.attempts()) == 1;
        }
        SubscriptionPayment current = memory.get(expected.paymentId());
        if (current == null || current.state() != expected.state() || current.attempts() != expected.attempts()) return false;
        memory.put(next.paymentId(), next);
        return true;
    }

    Optional<SubscriptionPayment> byId(UUID paymentId) {
        if (jdbc != null) return jdbc.query("SELECT " + COLUMNS + " FROM subscription_payment WHERE payment_id = ?", ROW, paymentId)
                .stream().findFirst();
        return Optional.ofNullable(memory.get(paymentId));
    }

    Optional<SubscriptionPayment> byIdempotencyKey(UUID userId, String key) {
        if (jdbc != null) return jdbc.query("SELECT " + COLUMNS + " FROM subscription_payment WHERE user_id = ? AND idempotency_key = ?",
                ROW, userId, key).stream().findFirst();
        return memory.values().stream().filter(p -> p.userId().equals(userId) && p.idempotencyKey().equals(key)).findFirst();
    }

    Optional<SubscriptionPayment> bySubscription(UUID subscriptionId) {
        if (jdbc != null) return jdbc.query("SELECT " + COLUMNS + " FROM subscription_payment WHERE subscription_id = ?", ROW,
                subscriptionId).stream().findFirst();
        return memory.values().stream().filter(p -> p.subscriptionId().equals(subscriptionId)).findFirst();
    }

    List<SubscriptionPayment> forUser(UUID userId) {
        if (jdbc != null) return jdbc.query("SELECT " + COLUMNS + " FROM subscription_payment WHERE user_id = ? "
                + "ORDER BY created_at DESC, payment_id", ROW, userId);
        return memory.values().stream().filter(p -> p.userId().equals(userId))
                .sorted(Comparator.comparing(SubscriptionPayment::createdAt).reversed()).toList();
    }

    /** Every subscription payment, for the dashboard totals check in engagement-service. */
    List<SubscriptionPayment> all() {
        if (jdbc != null) return jdbc.query("SELECT " + COLUMNS + " FROM subscription_payment ORDER BY created_at, payment_id", ROW);
        return memory.values().stream().sorted(Comparator.comparing(SubscriptionPayment::createdAt)
                .thenComparing(SubscriptionPayment::paymentId)).toList();
    }

    /**
     * Work for the reconciliation job: lost provider answers, abandoned PROCESSING rows (older than
     * {@code staleBefore}), outcomes engagement-service has not received yet, and pending refunds.
     */
    List<SubscriptionPayment> needingWork(Instant staleBefore) {
        if (jdbc != null) return jdbc.query("SELECT " + COLUMNS + " FROM subscription_payment WHERE state IN (?, ?) "
                        + "OR (state = ? AND updated_at < ?) OR (state IN (?, ?) AND outcome_delivered = FALSE) ORDER BY updated_at",
                ROW, State.REQUIRES_RECONCILIATION.name(), State.REFUND_PENDING.name(), State.PROCESSING.name(),
                time(staleBefore), State.SUCCEEDED.name(), State.FAILED.name());
        return memory.values().stream().filter(p -> p.state() == State.REQUIRES_RECONCILIATION
                        || p.state() == State.REFUND_PENDING
                        || p.state() == State.PROCESSING && p.updatedAt().isBefore(staleBefore)
                        || (p.state() == State.SUCCEEDED || p.state() == State.FAILED) && !p.outcomeDelivered())
                .sorted(Comparator.comparing(SubscriptionPayment::updatedAt)).toList();
    }

    private static OffsetDateTime time(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static SubscriptionPayment map(ResultSet rs, int row) throws SQLException {
        return new SubscriptionPayment(rs.getObject("payment_id", UUID.class), rs.getObject("subscription_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getLong("amount_minor"), rs.getString("currency").trim(),
                rs.getString("provider"), rs.getObject("method_id", UUID.class), rs.getString("test_scenario"),
                rs.getString("method_display"), rs.getString("idempotency_key"), rs.getString("request_hash"),
                State.valueOf(rs.getString("state")), rs.getString("provider_reference"), rs.getString("failure_code"),
                rs.getBoolean("outcome_delivered"), rs.getString("refund_reason"), rs.getString("refund_reference"),
                rs.getInt("attempts"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
