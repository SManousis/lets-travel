package com.travelplan.engagement;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Inserts rows directly, for tests that need data the API would take several steps to produce. */
final class Fixtures {
    private Fixtures() {}

    static UUID subscription(JdbcTemplate jdbc, UUID travelId, UUID travelerId, UUID managerId, String status,
                             LocalDate start, LocalDate end, String timeZone) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        jdbc.update("INSERT INTO subscription (subscription_id, travel_id, traveler_id, manager_id, travel_title, start_date, "
                        + "end_date, time_zone, cancellation_cutoff, price_amount_minor, price_currency, status, idempotency_key, "
                        + "seat_released, version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, travelId, travelerId, managerId, "Trip " + travelId, start, end, timeZone, start.minusDays(3), 10000,
                "EUR", status, UUID.randomUUID(), false, 1, now, now);
        return id;
    }

    static void feedback(JdbcTemplate jdbc, UUID managerId, String title, int rating, String comment, String createdAt) {
        UUID travelId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        UUID subscriptionId = subscription(jdbc, travelId, travelerId, managerId, "COMPLETED",
                LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 5), "UTC");
        jdbc.update("INSERT INTO feedback (feedback_id, subscription_id, travel_id, traveler_id, manager_id, travel_title, "
                        + "author_display_name, rating, comment, idempotency_key, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), subscriptionId, travelId, travelerId, managerId, title, "Secret Author", rating, comment,
                UUID.randomUUID(), OffsetDateTime.parse(createdAt));
    }

    static void report(JdbcTemplate jdbc, String targetType, UUID targetId, String status) {
        OffsetDateTime now = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        jdbc.update("INSERT INTO report (report_id, reporter_id, target_type, target_id, target_display_name, category, "
                        + "description, status, idempotency_key, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), UUID.randomUUID(), targetType, targetId, "Target", "OTHER", "private allegation text",
                status, UUID.randomUUID(), now, now);
    }
}
