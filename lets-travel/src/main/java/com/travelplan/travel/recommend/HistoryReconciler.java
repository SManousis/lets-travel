package com.travelplan.travel.recommend;

import com.travelplan.common.ServiceUrls;
import com.travelplan.travel.recommend.RecommendationGraph.HistoryRow;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Periodically copies engagement-service's current travel history (confirmed and completed subscriptions
 * with ratings) into the graph. This repairs anything a lost or failed event left behind (drift) and
 * rebuilds the whole history from scratch when the graph was emptied.
 */
@Component
public class HistoryReconciler {
    private static final int PAGE_SIZE = 500;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final RecommendationService recommendations;
    private final String engagementUrl;
    private final String serviceToken;

    HistoryReconciler(RecommendationService recommendations,
                      @Value("${services.engagement.url:}") String engagementUrl,
                      @Value("${security.internal-service-token:change-me-internal}") String serviceToken) {
        this.recommendations = recommendations;
        this.engagementUrl = ServiceUrls.withoutTrailingSlashes(engagementUrl);
        this.serviceToken = serviceToken;
    }

    @Scheduled(initialDelayString = "${recommendations.reconcile-initial-delay:PT1M}",
            fixedDelayString = "${recommendations.reconcile-interval:PT10M}")
    void scheduled() {
        if (!engagementUrl.isBlank()) reconcileNow();
    }

    public RecommendationService.ReconcileStatus reconcileNow() {
        return recommendations.reconcile(this::snapshot);
    }

    private List<HistoryRow> snapshot() {
        if (engagementUrl.isBlank()) throw new IllegalStateException("services.engagement.url is not configured");
        List<HistoryRow> rows = new ArrayList<>();
        for (int page = 0; ; page++) {
            JsonNode body = get("/internal/recommendation-history?page=" + page + "&size=" + PAGE_SIZE);
            for (JsonNode item : body.path("items")) {
                rows.add(new HistoryRow(UUID.fromString(item.path("subscriptionId").asString()),
                        UUID.fromString(item.path("travelerId").asString()), UUID.fromString(item.path("travelId").asString()),
                        item.path("status").asString(), item.path("version").asLong(),
                        item.path("rating").isNull() || item.path("rating").isMissingNode() ? null : item.path("rating").asInt()));
            }
            if (page + 1 >= body.path("totalPages").asInt(0)) return rows;
        }
    }

    private JsonNode get(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(engagementUrl + path)).timeout(Duration.ofSeconds(10))
                .header("X-Internal-Service-Token", serviceToken).GET().build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("engagement-service answered " + response.statusCode());
            return json.readTree(response.body());
        } catch (IOException failure) {
            throw new IllegalStateException("engagement-service unreachable", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", interrupted);
        }
    }
}
