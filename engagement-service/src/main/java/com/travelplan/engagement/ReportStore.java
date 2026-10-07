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

/** Reports and their append-only moderation history. Parameterised SQL only. */
@Repository
class ReportStore {
    private static final String COLUMNS = "report_id, reporter_id, target_type, target_id, target_display_name, category, "
            + "description, status, assignee_id, idempotency_key, created_at, updated_at";
    private static final RowMapper<Report> ROW = ReportStore::map;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    ReportStore(ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<PlatformTransactionManager> transactions) {
        this.jdbc = jdbc.getIfAvailable();
        PlatformTransactionManager manager = transactions.getIfAvailable();
        this.transactions = manager == null ? null : new TransactionTemplate(manager);
    }

    enum TargetType { TRAVEL_MANAGER, TRAVELER, TRAVEL }

    enum Category { FRAUD, HARASSMENT, SAFETY, MISLEADING_LISTING, INAPPROPRIATE_CONTENT, OTHER }

    /** OPEN -> IN_REVIEW -> RESOLVED (upheld) or DISMISSED. The last two are final. */
    enum Status {
        OPEN, IN_REVIEW, RESOLVED, DISMISSED;

        boolean closed() { return this == RESOLVED || this == DISMISSED; }
    }

    record Report(UUID reportId, UUID reporterId, TargetType targetType, UUID targetId, String targetDisplayName,
                  Category category, String description, Status status, UUID assigneeId, UUID idempotencyKey,
                  Instant createdAt, Instant updatedAt) {}

    record ModerationEntry(UUID adminId, Status status, String note, Instant at) {}

    record Page(List<Report> items, long total) {}

    static String openKey(UUID reporterId, TargetType type, UUID targetId) {
        return reporterId + ":" + type + ":" + targetId;
    }

    void requireAvailable() {
        if (jdbc == null || transactions == null) throw new ManagerPageService.DependencyUnavailable();
    }

    Optional<Report> byId(UUID id) {
        requireAvailable();
        return jdbc.query("SELECT " + COLUMNS + " FROM report WHERE report_id = ?", ROW, id).stream().findFirst();
    }

    Optional<Report> byIdempotencyKey(UUID reporterId, UUID key) {
        requireAvailable();
        return jdbc.query("SELECT " + COLUMNS + " FROM report WHERE reporter_id = ? AND idempotency_key = ?",
                ROW, reporterId, key).stream().findFirst();
    }

    boolean hasOpen(UUID reporterId, TargetType type, UUID targetId) {
        requireAvailable();
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM report WHERE open_key = ?", Long.class,
                openKey(reporterId, type, targetId));
        return count != null && count > 0;
    }

    long filedSince(UUID reporterId, Instant since) {
        requireAvailable();
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM report WHERE reporter_id = ? AND created_at >= ?",
                Long.class, reporterId, Outbox.time(since));
        return count == null ? 0 : count;
    }

    /** Upheld (RESOLVED) reports against a target: the only report figure shown outside moderation. */
    long upheldAgainst(TargetType type, UUID targetId) {
        requireAvailable();
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM report WHERE target_type = ? AND target_id = ? AND status = ?",
                Long.class, type.name(), targetId, Status.RESOLVED.name());
        return count == null ? 0 : count;
    }

    /** Inserts an OPEN report; a duplicate open report or idempotency key raises DuplicateKeyException. */
    void insert(Report report) {
        requireAvailable();
        transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO report (" + COLUMNS + ", open_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    report.reportId(), report.reporterId(), report.targetType().name(), report.targetId(),
                    report.targetDisplayName(), report.category().name(), report.description(), report.status().name(),
                    report.assigneeId(), report.idempotencyKey(), Outbox.time(report.createdAt()),
                    Outbox.time(report.updatedAt()), openKey(report.reporterId(), report.targetType(), report.targetId()));
            event("ReportFiled", report, 1);
        });
    }

    /** Applies a moderation step with its history entry; empty if the report is unknown or already closed. */
    Optional<Report> moderate(UUID id, Status to, UUID adminId, boolean assignToSelf, String note, Instant now) {
        requireAvailable();
        return transactions.execute(status -> {
            Optional<Report> current = jdbc.query("SELECT " + COLUMNS + " FROM report WHERE report_id = ? FOR UPDATE",
                    ROW, id).stream().findFirst();
            if (current.isEmpty() || current.get().status().closed()) return Optional.<Report>empty();
            Report old = current.get();
            UUID assignee = assignToSelf ? adminId : old.assigneeId();
            jdbc.update("UPDATE report SET status = ?, assignee_id = ?, open_key = ?, updated_at = ? WHERE report_id = ?",
                    to.name(), assignee, to.closed() ? null : openKey(old.reporterId(), old.targetType(), old.targetId()),
                    Outbox.time(now), id);
            // The row lock above serialises moderation of one report, so the step number is unique.
            Integer last = jdbc.queryForObject("SELECT COALESCE(MAX(step), 0) FROM report_moderation WHERE report_id = ?",
                    Integer.class, id);
            int step = (last == null ? 0 : last) + 1;
            jdbc.update("INSERT INTO report_moderation (entry_id, report_id, step, admin_id, status, note, occurred_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), id, step, adminId, to.name(), note, Outbox.time(now));
            Report updated = jdbc.queryForObject("SELECT " + COLUMNS + " FROM report WHERE report_id = ?", ROW, id);
            event("ReportModerated", updated, step + 1);
            return Optional.of(updated);
        });
    }

    List<ModerationEntry> history(UUID reportId) {
        requireAvailable();
        return jdbc.query("SELECT admin_id, status, note, occurred_at FROM report_moderation WHERE report_id = ? "
                        + "ORDER BY step", (rs, row) -> new ModerationEntry(rs.getObject("admin_id", UUID.class),
                        Status.valueOf(rs.getString("status")), rs.getString("note"), instant(rs, "occurred_at")), reportId);
    }

    Page byReporter(UUID reporterId, int page, int size) {
        requireAvailable();
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM report WHERE reporter_id = ?", Long.class, reporterId);
        List<Report> items = jdbc.query("SELECT " + COLUMNS + " FROM report WHERE reporter_id = ? "
                + "ORDER BY created_at DESC, report_id LIMIT ? OFFSET ?", ROW, reporterId, size, (long) page * size);
        return new Page(items, total == null ? 0 : total);
    }

    /** The moderation queue, oldest first; each filter is optional. */
    Page queue(Status statusFilter, TargetType typeFilter, int page, int size) {
        requireAvailable();
        List<String> clauses = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (statusFilter != null) { clauses.add("status = ?"); args.add(statusFilter.name()); }
        if (typeFilter != null) { clauses.add("target_type = ?"); args.add(typeFilter.name()); }
        String where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM report" + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<Report> items = jdbc.query("SELECT " + COLUMNS + " FROM report" + where
                + " ORDER BY created_at, report_id LIMIT ? OFFSET ?", ROW, args.toArray());
        return new Page(items, total == null ? 0 : total);
    }

    /** IDs, type, category and status only: the description and names never leave through events. */
    private void event(String type, Report report, long version) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("aggregateType", "Report");
        payload.put("producer", "engagement-service");
        payload.put("reportId", report.reportId().toString());
        payload.put("targetType", report.targetType().name());
        payload.put("targetId", report.targetId().toString());
        payload.put("category", report.category().name());
        payload.put("status", report.status().name());
        Outbox.write(jdbc, type, report.reportId(), version, report.updatedAt(), payload);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Report map(ResultSet rs, int row) throws SQLException {
        return new Report(rs.getObject("report_id", UUID.class), rs.getObject("reporter_id", UUID.class),
                TargetType.valueOf(rs.getString("target_type")), rs.getObject("target_id", UUID.class),
                rs.getString("target_display_name"), Category.valueOf(rs.getString("category")), rs.getString("description"),
                Status.valueOf(rs.getString("status")), rs.getObject("assignee_id", UUID.class),
                rs.getObject("idempotency_key", UUID.class), instant(rs, "created_at"), instant(rs, "updated_at"));
    }
}
