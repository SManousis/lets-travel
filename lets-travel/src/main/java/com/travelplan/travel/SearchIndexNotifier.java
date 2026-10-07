package com.travelplan.travel;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Tells search-service that a travel changed, after the Neo4j transaction has committed.
 *
 * The message carries only the travel ID: search-service fetches the travel's current state back
 * from /internal/search/travels/{id}, so a repeated or late notification is harmless. The call runs
 * on a background thread and never fails the travel request: if search-service is down the change
 * is simply missed until its periodic full rebuild repairs the index (docs/search.md).
 */
@Component
class SearchIndexNotifier {
    private static final Logger log = LoggerFactory.getLogger(SearchIndexNotifier.class);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "search-index-notifier");
        thread.setDaemon(true);
        return thread;
    });
    private final String searchUrl;
    private final String serviceToken;

    SearchIndexNotifier(@Value("${services.search.url:}") String searchUrl,
                        @Value("${security.internal-service-token:change-me-internal}") String serviceToken) {
        this.searchUrl = searchUrl.replaceAll("/+$", "");
        this.serviceToken = serviceToken;
    }

    /** Queues one notification; returns immediately. Without a configured search URL it does nothing. */
    void travelChanged(UUID travelId) {
        if (searchUrl.isBlank()) return;
        String requestId = ManagerIdentityClient.requestId();
        sender.execute(() -> send(travelId, requestId));
    }

    private void send(UUID travelId, String requestId) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(searchUrl + "/internal/search/index/travels/" + travelId))
                .timeout(Duration.ofSeconds(3))
                .header("X-Internal-Service-Token", serviceToken)
                .header("X-Request-ID", requestId)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status != 202) log.warn("Search index notification for travel {} answered {}", travelId, status);
        } catch (IOException ex) {
            log.warn("Search index notification for travel {} failed: {}", travelId, ex.getClass().getSimpleName());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void stop() {
        sender.shutdown();
    }
}
