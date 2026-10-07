package com.travelplan.engagement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Subscription rows in engagement's PostgreSQL database. Every state change writes the new row,
 * an audit history entry and an outbox event in one transaction. Without a database (the default,
 * infrastructure-free profile) every call fails with 503.
 */
@Repository
class SubscriptionStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COLUMNS = "subscription_id, travel_id, traveler_id, manager_id, travel_title, start_date, "
            + "end_date, time_zone, cancellation_cutoff, price_amount_minor, price_currency, status, idempotency_key, "
            + "payment_id, reservation_expires_at, cancellation_reason, cancelled_by, cancelled_at, seat_released, "
            + "refund_due, version, created_at, updated_at";
    private static final RowMapper<Subscription> ROW = SubscriptionStore::map;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    SubscriptionStore(ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<PlatformTransactionManager> transactions) {
        this.jdbc = jdbc.getIfAvailable();
        PlatformTransactionManager manager = transactions.getIfAvailable();
        this.transactions = manager == null ? null : new TransactionTemplate(manager);
    }

    /** Fails with 503 before any other work when the subscription database is not configured. */
    void requireAvailable() {
        if (jdbc == null || transactions == null) throw new ManagerPageService.DependencyUnavailable();
    }

    Optional<Subscription> byId(UUID id) {
        requireAvailable();
        return jdbc.query("SELECT " + COLUMNS + " FROM subscription WHERE subscription_id = ?", ROW, id).stream().findFirst();
    }

    Optional<Subscription> byIdempotencyKey(UUID travelerId, UUID key) {
        requireAvailable();
        return jdbc.query("SELECT " + COLUMNS + " FROM subscription WHERE traveler_id = ? AND idempotency_key = ?",
                ROW, travelerId, key).stream().findFirst();
    }

    Optional<Subscription> active(UUID travelId, UUID travelerId) {
        requireAvailable();
        return jdbc.query("SELECT " + COLUMNS + " FROM subscription WHERE active_key = ?", ROW,
                Subscription.activeKey(travelId, travelerId)).stream().findFirst();
    }

    /** Inserts a new subscription; a duplicate active subscription or idempotency key raises DuplicateKeyException. */
    void insert(Subscription subscription) {
        requireAvailable();
        transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO subscription (" + COLUMNS + ", active_key) VALUES "
                            + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    subscription.subscriptionId(), subscription.travelId(), subscription.travelerId(),
                    subscription.managerId(), subscription.travelTitle(), subscription.startDate(),
                    subscription.endDate(), subscription.timeZone(), subscription.cancellationCutoff(),
                    subscription.priceAmountMinor(), subscription.priceCurrency(), subscription.status().name(),
                    subscription.idempotencyKey(), subscription.paymentId(), time(subscription.reservationExpiresAt()),
                    subscription.cancellationReason(), subscription.cancelledBy(), time(subscription.cancelledAt()),
                    subscription.seatReleased(), subscription.refundDue(), subscription.version(), time(subscription.createdAt()),
                    time(subscription.updatedAt()), Subscription.activeKey(subscription.travelId(), subscription.travelerId()));
            history(subscription, null, subscription.travelerId(), null, subscription.createdAt());
            outbox("SubscriptionReserved", subscription);
        });
    }

    /** A status change requested by a caller, applied only if the row is still in one of {@code from}. */
    record Change(Subscription.Status to, UUID actorId, String reason, UUID paymentId, String eventType) {}

    /** Applies the change atomically; empty if another request changed the subscription first. */
    Optional<Subscription> transition(UUID id, Set<Subscription.Status> from, Change change, Instant now) {
        requireAvailable();
        return transactions.execute(status -> {
            Optional<Subscription> current = jdbc.query("SELECT " + COLUMNS + " FROM subscription WHERE subscription_id = ? FOR UPDATE",
                    ROW, id).stream().findFirst();
            if (current.isEmpty() || !from.contains(current.get().status())) return Optional.<Subscription>empty();
            Subscription old = current.get();
            boolean cancellation = change.to() == Subscription.Status.CANCELLED_BY_TRAVELER
                    || change.to() == Subscription.Status.CANCELLED_BY_MANAGER;
            // Cancelling a paid subscription makes a refund due (docs/payments.md, "Cancellation and refunds").
            boolean refundDue = old.refundDue() || cancellation && old.status() == Subscription.Status.CONFIRMED;
            jdbc.update("UPDATE subscription SET status = ?, active_key = ?, payment_id = ?, reservation_expires_at = ?, "
                            + "cancellation_reason = ?, cancelled_by = ?, cancelled_at = ?, refund_due = ?, version = version + 1, "
                            + "updated_at = ? WHERE subscription_id = ?",
                    change.to().name(), change.to().active() ? Subscription.activeKey(old.travelId(), old.travelerId()) : null,
                    change.paymentId() != null ? change.paymentId() : old.paymentId(),
                    change.to() == Subscription.Status.PENDING_PAYMENT ? time(old.reservationExpiresAt()) : null,
                    cancellation ? change.reason() : old.cancellationReason(),
                    cancellation ? change.actorId() : old.cancelledBy(),
                    cancellation ? time(now) : time(old.cancelledAt()), refundDue, time(now), id);
            Subscription updated = jdbc.queryForObject("SELECT " + COLUMNS + " FROM subscription WHERE subscription_id = ?", ROW, id);
            history(updated, old.status(), change.actorId(), change.reason(), now);
            outbox(change.eventType(), updated);
            return Optional.of(updated);
        });
    }

    void markSeatReleased(UUID id) {
        requireAvailable();
        jdbc.update("UPDATE subscription SET seat_released = TRUE WHERE subscription_id = ?", id);
    }

    /** payment-service accepted the refund request; nothing more is owed from this side. */
    void markRefundRequested(UUID id) {
        requireAvailable();
        jdbc.update("UPDATE subscription SET refund_due = FALSE WHERE subscription_id = ?", id);
    }

    /** Paid subscriptions that were cancelled but whose refund payment-service has not accepted yet. */
    List<Subscription> refundsDue() {
        requireAvailable();
        return jdbc.query("SELECT " + COLUMNS + " FROM subscription WHERE refund_due = TRUE", ROW);
    }

    /** Unpaid reservations whose payment window closed before {@code now}. */
    List<Subscription> pendingExpiredBy(Instant now) {
        requireAvailable();
        return jdbc.query("SELECT " + COLUMNS + " FROM subscription WHERE status = ? AND reservation_expires_at < ?", ROW,
                Subscription.Status.PENDING_PAYMENT.name(), time(now));
    }

    Page mine(UUID travelerId, Subscription.Status status, int page, int size) {
        return page("traveler_id = ?", travelerId, status, page, size);
    }

    Page forTravel(UUID travelId, Subscription.Status status, int page, int size) {
        return page("travel_id = ?", travelId, status, page, size);
    }

    long completedTrips(UUID travelerId) {
        requireAvailable();
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM subscription WHERE traveler_id = ? AND status = ?",
                Long.class, travelerId, Subscription.Status.COMPLETED.name());
        return count == null ? 0 : count;
    }

    /** Confirmed subscriptions whose travel ended on or before {@code endedBy} (callers refine per time zone). */
    List<Subscription> confirmedEndingBy(LocalDate endedBy) {
        requireAvailable();
        return jdbc.query("SELECT " + COLUMNS + " FROM subscription WHERE status = ? AND end_date <= ?", ROW,
                Subscription.Status.CONFIRMED.name(), endedBy);
    }

    /** Cancelled or failed subscriptions whose place travel-service has not yet confirmed as released. */
    List<Subscription> unreleasedPlaces() {
        requireAvailable();
        return jdbc.query("SELECT " + COLUMNS + " FROM subscription WHERE seat_released = FALSE AND status IN (?, ?, ?)", ROW,
                Subscription.Status.PAYMENT_FAILED.name(), Subscription.Status.CANCELLED_BY_TRAVELER.name(),
                Subscription.Status.CANCELLED_BY_MANAGER.name());
    }

    List<HistoryEntry> history(UUID subscriptionId) {
        requireAvailable();
        return jdbc.query("SELECT from_status, to_status, actor_id, reason, occurred_at FROM subscription_history "
                        + "WHERE subscription_id = ? ORDER BY occurred_at, to_status", (rs, row) -> new HistoryEntry(
                        rs.getString("from_status"), rs.getString("to_status"), rs.getObject("actor_id", UUID.class),
                        rs.getString("reason"), instant(rs, "occurred_at")), subscriptionId);
    }

    record HistoryEntry(String fromStatus, String toStatus, UUID actorId, String reason, Instant occurredAt) {}

    record Page(List<Subscription> items, long total) {}

    private Page page(String ownerClause, UUID owner, Subscription.Status status, int page, int size) {
        requireAvailable();
        String where = " WHERE " + ownerClause + (status == null ? "" : " AND status = ?");
        List<Object> args = new ArrayList<>(List.of(owner));
        if (status != null) args.add(status.name());
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM subscription" + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<Subscription> items = jdbc.query("SELECT " + COLUMNS + " FROM subscription" + where
                + " ORDER BY created_at DESC, subscription_id LIMIT ? OFFSET ?", ROW, args.toArray());
        return new Page(items, total == null ? 0 : total);
    }

    private void history(Subscription subscription, Subscription.Status from, UUID actor, String reason, Instant at) {
        jdbc.update("INSERT INTO subscription_history (history_id, subscription_id, from_status, to_status, actor_id, reason, "
                        + "occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), subscription.subscriptionId(),
                from == null ? null : from.name(), subscription.status().name(), actor, reason, time(at));
    }

    /**
     * Writes the lifecycle event in the same transaction as the change (transactional outbox). The
     * payload carries IDs, dates, price and status only; never names, contact details or the reason text.
     */
    private void outbox(String eventType, Subscription subscription) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("aggregateType", "Subscription");
        payload.put("producer", "engagement-service");
        payload.put("subscriptionId", subscription.subscriptionId().toString());
        payload.put("travelId", subscription.travelId().toString());
        payload.put("travelerId", subscription.travelerId().toString());
        payload.put("managerId", subscription.managerId().toString());
        payload.put("status", subscription.status().name());
        payload.put("startDate", subscription.startDate().toString());
        payload.put("endDate", subscription.endDate().toString());
        payload.put("amountMinor", subscription.priceAmountMinor());
        payload.put("currency", subscription.priceCurrency());
        payload.put("paymentId", subscription.paymentId() == null ? null : subscription.paymentId().toString());
        jdbc.update("INSERT INTO outbox_event (event_id, event_type, aggregate_id, aggregate_version, occurred_at, trace_id, payload) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), eventType, subscription.subscriptionId(),
                subscription.version(), time(subscription.updatedAt()), InternalCalls.requestId(),
                JSON.writeValueAsString(payload));
    }

    private static OffsetDateTime time(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Subscription map(ResultSet rs, int row) throws SQLException {
        return new Subscription(rs.getObject("subscription_id", UUID.class), rs.getObject("travel_id", UUID.class),
                rs.getObject("traveler_id", UUID.class), rs.getObject("manager_id", UUID.class),
                rs.getString("travel_title"), rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class), rs.getString("time_zone"),
                rs.getObject("cancellation_cutoff", LocalDate.class), rs.getLong("price_amount_minor"),
                rs.getString("price_currency").trim(), Subscription.Status.valueOf(rs.getString("status")),
                rs.getObject("idempotency_key", UUID.class), rs.getObject("payment_id", UUID.class),
                instant(rs, "reservation_expires_at"), rs.getString("cancellation_reason"),
                rs.getObject("cancelled_by", UUID.class), instant(rs, "cancelled_at"), rs.getBoolean("seat_released"),
                rs.getBoolean("refund_due"), rs.getLong("version"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }
}
