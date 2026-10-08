package com.travelplan.search.index;

import com.travelplan.common.ServiceUrls;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Reads travels from travel-service, the source of truth, through its private
 * /internal/search/travels endpoints (machine token, request ID forwarded like every internal call).
 */
@Component
public class TravelServiceClient {
    private static final int PAGE_SIZE = 200;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String travelUrl;
    private final String serviceToken;

    public TravelServiceClient(@Value("${services.travel.url:http://127.0.0.1:8083}") String travelUrl,
                               @Value("${security.internal-service-token:change-me-internal}") String serviceToken) {
        this.travelUrl = ServiceUrls.withoutTrailingSlashes(travelUrl);
        this.serviceToken = serviceToken;
    }

    /** The travel's current state, or empty when travel-service no longer has it (deleted). */
    public Optional<TravelDocument> travel(UUID travelId) {
        HttpResponse<String> response = get("/internal/search/travels/" + travelId);
        if (response.statusCode() == 404) return Optional.empty();
        if (response.statusCode() != 200) throw new TravelServiceUnavailable("travel-service answered " + response.statusCode());
        return Optional.of(parse(response.body(), TravelDocument.class));
    }

    /** Every bookable travel, read page by page. */
    public List<TravelDocument> bookableTravels() {
        List<TravelDocument> all = new ArrayList<>();
        for (int page = 0; ; page++) {
            HttpResponse<String> response = get("/internal/search/travels?page=" + page + "&size=" + PAGE_SIZE);
            if (response.statusCode() != 200) throw new TravelServiceUnavailable("travel-service answered " + response.statusCode());
            JsonNode body = parse(response.body(), JsonNode.class);
            List<TravelDocument> items = body.path("items").valueStream()
                    .map(item -> TravelIndex.JSON.convertValue(item, TravelDocument.class)).toList();
            all.addAll(items);
            if (items.isEmpty() || all.size() >= body.path("total").asLong(0)) return all;
        }
    }

    private HttpResponse<String> get(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(travelUrl + path)).timeout(Duration.ofSeconds(10))
                .header("X-Internal-Service-Token", serviceToken)
                .header("X-Request-ID", UUID.randomUUID().toString())
                .GET().build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new TravelServiceUnavailable("travel-service is unreachable");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TravelServiceUnavailable("interrupted");
        }
    }

    private static <T> T parse(String body, Class<T> type) {
        try {
            return TravelIndex.JSON.readValue(body, type);
        } catch (JacksonException ex) {
            throw new TravelServiceUnavailable("travel-service sent an unreadable travel");
        }
    }

    /** travel-service could not be asked; the change stays queued and is retried. */
    public static class TravelServiceUnavailable extends RuntimeException {
        public TravelServiceUnavailable(String message) {
            super(message);
        }
    }
}
