package com.travelplan.engagement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Feedback rows. Every query uses JdbcTemplate placeholders (?), so user text is always sent to
 * PostgreSQL as a value and never becomes part of the SQL statement.
 */
@Repository
class FeedbackStore {
    static final String FORMER_TRAVELER = "Former traveler";
    private static final String COLUMNS = "feedback_id, subscription_id, travel_id, traveler_id, manager_id, travel_title, "
            + "author_display_name, rating, comment, idempotency_key, created_at";
    private static final RowMapper<Feedback> ROW = FeedbackStore::map;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    FeedbackStore(ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<PlatformTransactionManager> transactions) {
        this.jdbc = jdbc.getIfAvailable();
        PlatformTransactionManager manager = transactions.getIfAvailable();
        this.transactions = manager == null ? null : new TransactionTemplate(manager);
    }

    record Feedback(UUID feedbackId, UUID subscriptionId, UUID travelId, UUID travelerId, UUID managerId,
                    String travelTitle, String authorDisplayName, int rating, String comment, UUID idempotencyKey,
                    Instant createdAt) {}

    record Page(List<Feedback> items, long total) {}

    record RatingSummary(Double average, long count) {}

    void requireAvailable() {
        if (jdbc == null || transactions == null) throw new ManagerPageService.DependencyUnavailable();
    }

    Optional<Feedback> byIdempotencyKey(UUID travelerId, UUID key) {
        requireAvailable();
        return jdbc.query("SELECT " + COLUMNS + " FROM feedback WHERE traveler_id = ? AND idempotency_key = ?",
                ROW, travelerId, key).stream().findFirst();
    }

    boolean exists(UUID travelerId, UUID travelId) {
        requireAvailable();
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM feedback WHERE traveler_id = ? AND travel_id = ?",
                Long.class, travelerId, travelId);
        return count != null && count > 0;
    }

    /** Inserts the feedback and its FeedbackSubmitted event; a second feedback raises DuplicateKeyException. */
    void insert(Feedback feedback) {
        requireAvailable();
        transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO feedback (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    feedback.feedbackId(), feedback.subscriptionId(), feedback.travelId(), feedback.travelerId(),
                    feedback.managerId(), feedback.travelTitle(), feedback.authorDisplayName(), feedback.rating(),
                    feedback.comment(), feedback.idempotencyKey(), Outbox.time(feedback.createdAt()));
            // IDs and the rating only: the comment and the author's name stay in this service.
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("schemaVersion", 1);
            payload.put("aggregateType", "Feedback");
            payload.put("producer", "engagement-service");
            payload.put("feedbackId", feedback.feedbackId().toString());
            payload.put("travelId", feedback.travelId().toString());
            payload.put("travelerId", feedback.travelerId().toString());
            payload.put("managerId", feedback.managerId().toString());
            payload.put("rating", feedback.rating());
            Outbox.write(jdbc, "FeedbackSubmitted", feedback.feedbackId(), 1, feedback.createdAt(), payload);
        });
    }

    /**
     * Account deletion: the rating stays (it is part of the manager's statistics) but no longer carries the
     * author's name or their own words.
     */
    int anonymizeAuthor(UUID travelerId) {
        requireAvailable();
        return jdbc.update("UPDATE feedback SET author_display_name = ?, comment = NULL WHERE traveler_id = ?",
                FORMER_TRAVELER, travelerId);
    }

    /** Feedback newest first; each filter is optional (null means "any"). */
    Page find(UUID travelId, UUID managerId, UUID travelerId, int page, int size) {
        requireAvailable();
        List<String> clauses = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (travelId != null) { clauses.add("travel_id = ?"); args.add(travelId); }
        if (managerId != null) { clauses.add("manager_id = ?"); args.add(managerId); }
        if (travelerId != null) { clauses.add("traveler_id = ?"); args.add(travelerId); }
        String where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM feedback" + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<Feedback> items = jdbc.query("SELECT " + COLUMNS + " FROM feedback" + where
                + " ORDER BY created_at DESC, feedback_id LIMIT ? OFFSET ?", ROW, args.toArray());
        return new Page(items, total == null ? 0 : total);
    }

    RatingSummary ratingFor(UUID managerId) {
        requireAvailable();
        return jdbc.queryForObject("SELECT AVG(CAST(rating AS DOUBLE PRECISION)) AS average, COUNT(*) AS total "
                        + "FROM feedback WHERE manager_id = ?",
                (rs, row) -> new RatingSummary(rs.getObject("average") == null ? null : rs.getDouble("average"),
                        rs.getLong("total")), managerId);
    }

    List<Feedback> recentFor(UUID managerId, int limit) {
        requireAvailable();
        return jdbc.query("SELECT " + COLUMNS + " FROM feedback WHERE manager_id = ? ORDER BY created_at DESC, feedback_id LIMIT ?",
                ROW, managerId, limit);
    }

    private static Feedback map(ResultSet rs, int row) throws SQLException {
        OffsetDateTime created = rs.getObject("created_at", OffsetDateTime.class);
        return new Feedback(rs.getObject("feedback_id", UUID.class), rs.getObject("subscription_id", UUID.class),
                rs.getObject("travel_id", UUID.class), rs.getObject("traveler_id", UUID.class),
                rs.getObject("manager_id", UUID.class), rs.getString("travel_title"), rs.getString("author_display_name"),
                rs.getInt("rating"), rs.getString("comment"), rs.getObject("idempotency_key", UUID.class),
                created == null ? null : created.toInstant());
    }
}
