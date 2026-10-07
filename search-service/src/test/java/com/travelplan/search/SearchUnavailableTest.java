package com.travelplan.search;

import com.sun.net.httpserver.HttpServer;
import com.travelplan.common.security.AccessTokenCodec;
import com.travelplan.common.security.Role;
import com.travelplan.common.testing.AuthorizationMatrix;
import com.travelplan.search.index.TravelIndexer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With no Elasticsearch at all (runs without Docker): search answers a safe 503 that tells the client to
 * browse instead, and travel changes are still accepted and queued until the index is back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0", "security.internal-service-token=test-internal",
        "spring.elasticsearch.uris=http://127.0.0.1:1", "services.travel.url=http://127.0.0.1:1",
        "search.sync.apply-initial-delay=PT1H", "search.sync.rebuild-initial-delay=PT1H"})
class SearchUnavailableTest {
    private static final HttpServer AUTH = AuthorizationMatrix.activeSessionsAuthServer();
    @Autowired AccessTokenCodec codec;
    @Autowired TravelIndexer indexer;
    @Value("${local.server.port}") int port;

    @DynamicPropertySource
    static void authUrl(DynamicPropertyRegistry registry) {
        registry.add("auth.service-url", () -> "http://127.0.0.1:" + AUTH.getAddress().getPort());
    }

    @AfterAll static void stopAuth() { AUTH.stop(0); }

    @Test void searchAndAutocompleteAnswerSearchUnavailable() throws Exception {
        for (String path : new String[] {"/api/search/travels?q=crete", "/api/search/autocomplete?q=cre"}) {
            HttpResponse<String> response = send("GET", path, "Bearer " + token(Role.TRAVELER), null);
            assertThat(response.statusCode()).as(path).isEqualTo(503);
            assertThat(JsonMapper.builder().build().readTree(response.body()).get("code").asString())
                    .isEqualTo("SEARCH_UNAVAILABLE");
            assertThat(response.headers().firstValue("Retry-After")).contains("30");
        }
    }

    @Test void changesAreQueuedWhileTheIndexIsDown() throws Exception {
        UUID travel = UUID.randomUUID();
        assertThat(send("POST", "/internal/search/index/travels/" + travel, null, "test-internal").statusCode()).isEqualTo(202);
        assertThat(send("POST", "/internal/search/index/travels/" + travel, null, "wrong").statusCode()).isEqualTo(403);
        indexer.applyPendingChanges(); // travel-service is unreachable too: the change stays queued
        TravelIndexer.Status status = indexer.status();
        assertThat(status.elasticsearchAvailable()).isFalse();
        assertThat(status.pendingChanges()).isGreaterThanOrEqualTo(1);
        assertThat(status.failingChanges()).extracting(TravelIndexer.Failure::travelId).contains(travel);
    }

    private String token(Role role) {
        return codec.issue(UUID.randomUUID(), role, UUID.randomUUID()).value();
    }

    private HttpResponse<String> send(String method, String path, String bearer, String serviceToken) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30)).method(method, HttpRequest.BodyPublishers.noBody());
        if (bearer != null) request.header("Authorization", bearer);
        if (serviceToken != null) request.header("X-Internal-Service-Token", serviceToken);
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
