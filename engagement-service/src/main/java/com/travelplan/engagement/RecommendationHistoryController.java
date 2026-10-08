package com.travelplan.engagement;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The current travel history for travel-service's recommendation reconciliation: every confirmed or
 * completed subscription and the traveler's rating of that travel. IDs, statuses and ratings only.
 * Never routed by the gateway; the shared token filter requires X-Internal-Service-Token.
 */
@RestController
class RecommendationHistoryController {
    private final JdbcTemplate jdbc;

    RecommendationHistoryController(ObjectProvider<JdbcTemplate> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    @GetMapping("/internal/recommendation-history")
    SubscriptionController.PageView<Row> history(@RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "500") int size) {
        if (page < 0 || size < 1 || size > 1000) throw new IllegalArgumentException("Invalid page");
        if (jdbc == null) return SubscriptionController.PageView.of(List.of(), page, size, 0);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM subscription WHERE status IN ('CONFIRMED', 'COMPLETED')", Long.class);
        List<Row> rows = jdbc.query("SELECT s.subscription_id, s.traveler_id, s.travel_id, s.status, s.version, f.rating "
                        + "FROM subscription s LEFT JOIN feedback f ON f.traveler_id = s.traveler_id AND f.travel_id = s.travel_id "
                        + "WHERE s.status IN ('CONFIRMED', 'COMPLETED') ORDER BY s.subscription_id LIMIT ? OFFSET ?",
                (rs, row) -> new Row(rs.getObject("subscription_id", UUID.class), rs.getObject("traveler_id", UUID.class),
                        rs.getObject("travel_id", UUID.class), rs.getString("status"), rs.getLong("version"),
                        rs.getObject("rating") == null ? null : rs.getInt("rating")),
                size, (long) page * size);
        return SubscriptionController.PageView.of(rows, page, size, total);
    }

    record Row(UUID subscriptionId, UUID travelerId, UUID travelId, String status, long version, Integer rating) {}
}
