package com.travelplan.search.index;

import co.elastic.clients.transport.rest5_client.low_level.Request;
import co.elastic.clients.transport.rest5_client.low_level.Cancellable;
import co.elastic.clients.transport.rest5_client.low_level.Response;
import co.elastic.clients.transport.rest5_client.low_level.ResponseException;
import co.elastic.clients.transport.rest5_client.low_level.ResponseListener;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * Every call search-service makes to Elasticsearch, as plain REST requests with JSON bodies, so the
 * requests read the same as in the Elasticsearch documentation.
 *
 * Searches and single-travel writes go through the alias {@value #ALIAS}. The alias points at one
 * concrete, versioned index such as {@code travels-v1-1759830000000}; a rebuild fills a new index and
 * then moves the alias in one atomic step, so searches never see a half-built index.
 */
@Component
public class TravelIndex {
    public static final String ALIAS = "travels";
    /** Bump when travel-index-vN.json changes incompatibly; the next rebuild then creates the new layout. */
    public static final String MAPPING_VERSION = "v1";
    static final String INDEX_PREFIX = ALIAS + "-" + MAPPING_VERSION + "-";
    public static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private static final int BULK_CHUNK = 500;
    private final Rest5Client client;
    private final Duration requestTimeout;
    private final String mapping;

    /**
     * {@code requestTimeout} is a deadline for every call, so a hung Elasticsearch turns into a quick
     * 503 instead of a request that never finishes.
     */
    public TravelIndex(Rest5Client client, @Value("${search.elasticsearch.request-timeout:5s}") Duration requestTimeout) {
        this.client = client;
        this.requestTimeout = requestTimeout;
        try (InputStream in = new ClassPathResource("elasticsearch/travel-index-" + MAPPING_VERSION + ".json").getInputStream()) {
            this.mapping = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Travel index mapping is missing", ex);
        }
    }

    /** The concrete index the alias points at, or null before the first rebuild. */
    public String currentIndex() {
        Result result = send("GET", "/_alias/" + ALIAS, null);
        if (result.status() == 404) return null;
        var names = result.body().propertyNames().iterator();
        return names.hasNext() ? names.next() : null;
    }

    public void createIndex(String name) {
        require(send("PUT", "/" + name, mapping), "create index " + name);
    }

    /**
     * Writes many travels in one bulk request. External versioning ({@code version_type=external_gte})
     * makes Elasticsearch refuse a write that carries an older travel version than the stored one.
     */
    public void bulkIndex(String index, List<TravelDocument> documents) {
        for (int from = 0; from < documents.size(); from += BULK_CHUNK)
            bulkChunk(index, documents.subList(from, Math.min(from + BULK_CHUNK, documents.size())));
    }

    private void bulkChunk(String index, List<TravelDocument> documents) {
        StringBuilder body = new StringBuilder();
        for (TravelDocument document : documents) {
            body.append(JSON.writeValueAsString(Map.of("index", Map.of("_index", index, "_id", document.travelId().toString(),
                    "version", version(document), "version_type", "external_gte")))).append('\n');
            body.append(JSON.writeValueAsString(document)).append('\n');
        }
        Result result = require(send("POST", "/_bulk", body.toString()), "bulk index");
        if (result.body().path("errors").asBoolean(false)) {
            for (JsonNode item : result.body().path("items")) {
                JsonNode outcome = item.path("index");
                if (outcome.has("error") && outcome.path("status").asInt(0) != 409)
                    throw new IllegalStateException("Bulk indexing failed: " + outcome.path("error").path("type").asString(""));
            }
        }
    }

    public void refresh(String index) {
        require(send("POST", "/" + index + "/_refresh", null), "refresh");
    }

    /** Moves the alias from the old index to the new one in a single atomic request. */
    public void switchAlias(String previous, String next) {
        List<Map<String, Object>> actions = new ArrayList<>();
        if (previous != null) actions.add(Map.of("remove", Map.of("index", previous, "alias", ALIAS)));
        actions.add(Map.of("add", Map.of("index", next, "alias", ALIAS)));
        require(send("POST", "/_aliases", JSON.writeValueAsString(Map.of("actions", actions))), "switch alias");
    }

    /** Deletes versioned indices older than {@code keep} (left over from earlier rebuilds). */
    public void deleteIndicesOlderThan(String keep) {
        Result result = require(send("GET", "/_cat/indices/" + ALIAS + "-*?format=json&h=index", null), "list indices");
        for (JsonNode row : result.body()) {
            String name = row.path("index").asString("");
            if (name.startsWith(ALIAS + "-") && name.compareTo(keep) < 0) deleteIndex(name);
        }
    }

    public void deleteIndex(String name) {
        Result result = send("DELETE", "/" + name, null);
        if (result.status() != 404) require(result, "delete index " + name);
    }

    /**
     * Adds or replaces one travel through the alias. An older version than the stored one is ignored.
     * {@code refresh=wait_for} returns once the change is visible to searches (at most about a second).
     */
    public void put(TravelDocument document) {
        Result result = send("PUT", "/" + ALIAS + "/_doc/" + document.travelId() + "?version=" + version(document)
                + "&version_type=external_gte&require_alias=true&refresh=wait_for", JSON.writeValueAsString(document));
        if (result.status() != 409) require(result, "index travel");
    }

    /** Removes one travel; removing a travel that is not indexed is fine. */
    public void remove(UUID travelId) {
        Result result = send("DELETE", "/" + ALIAS + "/_doc/" + travelId + "?refresh=wait_for", null);
        if (result.status() != 404) require(result, "delete travel");
    }

    public JsonNode search(Map<String, Object> query) {
        Result result = send("POST", "/" + ALIAS + "/_search", JSON.writeValueAsString(query));
        if (result.status() == 404) throw new SearchUnavailable("The travel index has not been built yet", null);
        return require(result, "search").body();
    }

    public long count() {
        Result result = send("GET", "/" + ALIAS + "/_count", null);
        if (result.status() == 404) return 0;
        return require(result, "count").body().path("count").asLong(0);
    }

    private static long version(TravelDocument document) {
        return document.version() == null ? 1 : document.version();
    }

    /**
     * Sends a request and waits at most {@code requestTimeout}. A network failure or a timeout becomes
     * SearchUnavailable; an HTTP error answer is returned to the caller to decide.
     */
    private Result send(String method, String endpoint, String json) {
        Request request = new Request(method, endpoint);
        if (json != null) request.setJsonEntity(json);
        CompletableFuture<Response> answer = new CompletableFuture<>();
        Cancellable call = client.performRequestAsync(request, new ResponseListener() {
            @Override public void onSuccess(Response response) { answer.complete(response); }
            @Override public void onFailure(Exception failure) { answer.completeExceptionally(failure); }
        });
        try {
            return read(answer.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException ex) {
            call.cancel();
            throw new SearchUnavailable("Elasticsearch did not answer within " + requestTimeout, ex);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof ResponseException error) return read(error.getResponse());
            throw new SearchUnavailable("Elasticsearch is unreachable", ex.getCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SearchUnavailable("Interrupted", ex);
        }
    }

    private static Result read(Response response) {
        try {
            int status = response.getStatusCode();
            if (response.getEntity() == null) return new Result(status, MissingNode.getInstance());
            try (InputStream in = response.getEntity().getContent()) {
                byte[] bytes = in.readAllBytes();
                return new Result(status, bytes.length == 0 ? MissingNode.getInstance() : JSON.readTree(bytes));
            }
        } catch (IOException ex) {
            throw new SearchUnavailable("Elasticsearch answer could not be read", ex);
        }
    }

    private static Result require(Result result, String action) {
        if (result.status() >= 500) throw new SearchUnavailable("Elasticsearch failed to " + action, null);
        if (result.status() >= 300)
            throw new IllegalStateException("Elasticsearch refused to " + action + ": HTTP " + result.status()
                    + " " + result.body().path("error").path("type").asString(""));
        return result;
    }

    private record Result(int status, JsonNode body) {}
}
