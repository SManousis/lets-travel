package com.travelplan.engagement.dashboard;

import com.travelplan.engagement.dashboard.DashboardFacts.FeedbackFact;
import com.travelplan.engagement.dashboard.DashboardFacts.ReportFact;
import com.travelplan.engagement.dashboard.DashboardFacts.SubscriptionFact;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads the dashboard facts from engagement-service's own tables in one go. A subscription's payment time is
 * when its history first records CONFIRMED; its refund time is when it was cancelled from CONFIRMED.
 */
@Component
public class DashboardStore {
    private static final String SUBSCRIPTIONS = "SELECT s.subscription_id, s.travel_id, s.manager_id, s.travel_title, "
            + "s.start_date, s.end_date, s.traveler_id, s.status, s.price_amount_minor, s.price_currency, "
            + "(SELECT MIN(h.occurred_at) FROM subscription_history h WHERE h.subscription_id = s.subscription_id "
            + "AND h.to_status = 'CONFIRMED') AS paid_at, "
            + "(SELECT MIN(h.occurred_at) FROM subscription_history h WHERE h.subscription_id = s.subscription_id "
            + "AND h.from_status = 'CONFIRMED' AND h.to_status IN ('CANCELLED_BY_TRAVELER', 'CANCELLED_BY_MANAGER')) AS refunded_at "
            + "FROM subscription s";

    private final JdbcTemplate jdbc;

    public DashboardStore(ObjectProvider<JdbcTemplate> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    public boolean available() {
        return jdbc != null;
    }

    public DashboardFacts facts() {
        return new DashboardFacts(
                jdbc.query(SUBSCRIPTIONS, (rs, row) -> new SubscriptionFact(uuid(rs, "subscription_id"), uuid(rs, "travel_id"),
                        uuid(rs, "manager_id"), rs.getString("travel_title"), rs.getObject("start_date", java.time.LocalDate.class),
                        rs.getObject("end_date", java.time.LocalDate.class), uuid(rs, "traveler_id"), rs.getString("status"),
                        rs.getLong("price_amount_minor"), rs.getString("price_currency").trim(), instant(rs, "paid_at"),
                        instant(rs, "refunded_at"))),
                jdbc.query("SELECT travel_id, manager_id, traveler_id, rating, created_at FROM feedback",
                        (rs, row) -> new FeedbackFact(uuid(rs, "travel_id"), uuid(rs, "manager_id"), uuid(rs, "traveler_id"),
                                rs.getInt("rating"), instant(rs, "created_at"))),
                jdbc.query("SELECT reporter_id, target_type, target_id, status, created_at FROM report",
                        (rs, row) -> new ReportFact(uuid(rs, "reporter_id"), rs.getString("target_type"), uuid(rs, "target_id"),
                                rs.getString("status"), instant(rs, "created_at"))));
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
