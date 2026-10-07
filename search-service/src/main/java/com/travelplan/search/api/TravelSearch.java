package com.travelplan.search.api;

import com.travelplan.search.index.TravelDocument;
import com.travelplan.search.index.TravelIndex;
import com.travelplan.search.index.TravelIndexer;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns validated API parameters into Elasticsearch queries and the answers into API responses.
 *
 * The client's text is only ever placed inside {@code match}-style queries as a plain value, never
 * parsed as Elasticsearch query syntax, so characters such as {@code * : " ( )} have no special meaning.
 */
@Service
public class TravelSearch {
    private static final JsonMapper JSON = TravelIndex.JSON;

    /** Main text fields and their weight: a hit in the title counts more than one in an accommodation address. */
    static final List<String> TEXT_FIELDS = List.of("title^4", "destinations.name^3", "destinations.country^2",
            "activities.name^2", "managerName^2", "description", "activities.description", "accommodations.name",
            "accommodations.address", "transports.mode", "transports.departure", "transports.arrival");
    static final List<String> PREFIX_FIELDS = List.of("title.prefix^4", "destinations.name.prefix^3",
            "destinations.country.prefix^2", "activities.name.prefix^2");
    private static final List<String> HIGHLIGHT_FIELDS = List.of("description", "activities.name",
            "activities.description", "accommodations.name", "accommodations.address", "transports.mode",
            "transports.departure", "transports.arrival", "managerName");

    private final TravelIndex index;
    private final TravelIndexer indexer;

    public TravelSearch(TravelIndex index, TravelIndexer indexer) {
        this.index = index;
        this.indexer = indexer;
    }

    public HitPage search(SearchQuery query) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("query", Map.of("bool", Map.of("must", List.of(textQuery(query.text())), "filter", filters(query))));
        request.put("sort", sort(query));
        request.put("from", query.page() * query.size());
        request.put("size", query.size());
        request.put("track_total_hits", true);
        if (query.text() != null) request.put("highlight", Map.of(
                "pre_tags", List.of(""), "post_tags", List.of(""), "fragment_size", 80, "number_of_fragments", 1,
                "fields", HIGHLIGHT_FIELDS.stream().collect(LinkedHashMap::new, (map, field) -> map.put(field, Map.of()), Map::putAll)));
        JsonNode answer = index.search(request);

        List<TravelHit> items = new ArrayList<>();
        for (JsonNode hit : answer.path("hits").path("hits")) {
            TravelDocument travel = JSON.convertValue(hit.path("_source"), TravelDocument.class);
            List<String> highlights = new ArrayList<>();
            for (Map.Entry<String, JsonNode> field : hit.path("highlight").properties())
                for (JsonNode fragment : field.getValue()) highlights.add(fragment.asString(""));
            items.add(TravelHit.of(travel, highlights));
        }
        long total = answer.path("hits").path("total").path("value").asLong(0);
        int totalPages = (int) ((total + query.size() - 1) / query.size());
        return new HitPage(items, query.page(), query.size(), total, totalPages, indexer.lastAppliedAt());
    }

    /**
     * Three tiers, best first: the whole text as an exact phrase, every word as a prefix (edge n-grams),
     * then every word with typo tolerance (fuzzy). A travel matching a higher tier scores higher, so exact
     * and prefix matches rank above fuzzy ones. An empty text matches everything.
     */
    private static Map<String, Object> textQuery(String text) {
        if (text == null) return Map.of("match_all", Map.of());
        return Map.of("bool", Map.of("minimum_should_match", 1, "should", List.of(
                Map.of("multi_match", Map.of("query", text, "type", "phrase", "fields", TEXT_FIELDS, "boost", 6)),
                Map.of("multi_match", Map.of("query", text, "type", "cross_fields", "operator", "and",
                        "fields", PREFIX_FIELDS, "boost", 3)),
                Map.of("multi_match", Map.of("query", text, "type", "best_fields", "operator", "and",
                        "fuzziness", "AUTO", "prefix_length", 1, "fields", TEXT_FIELDS)))));
    }

    /** Filters narrow the result without changing its score. Only bookable, future travels are ever returned. */
    private static List<Map<String, Object>> filters(SearchQuery query) {
        List<Map<String, Object>> filters = new ArrayList<>();
        filters.add(Map.of("term", Map.of("bookable", true)));
        filters.add(Map.of("range", Map.of("startDate", Map.of("gt", "now/d"))));
        if (query.country() != null) filters.add(Map.of("term", Map.of("destinations.country.exact", query.country())));
        if (query.transportMode() != null) filters.add(Map.of("term", Map.of("transports.mode.exact", query.transportMode())));
        if (query.currency() != null) filters.add(Map.of("term", Map.of("currency", query.currency())));
        Map<String, Object> dates = new LinkedHashMap<>();
        if (query.startFrom() != null) dates.put("gte", query.startFrom().toString());
        if (query.startTo() != null) dates.put("lte", query.startTo().toString());
        if (!dates.isEmpty()) filters.add(Map.of("range", Map.of("startDate", dates)));
        Map<String, Object> prices = new LinkedHashMap<>();
        if (query.minPrice() != null) prices.put("gte", query.minPrice());
        if (query.maxPrice() != null) prices.put("lte", query.maxPrice());
        if (!prices.isEmpty()) filters.add(Map.of("range", Map.of("price", prices)));
        return filters;
    }

    /** Every sort ends with the travel ID, so equal values always come back in the same order. */
    private static List<Object> sort(SearchQuery query) {
        List<Object> sort = new ArrayList<>();
        switch (query.sort()) {
            case RELEVANCE -> sort.add(Map.of("_score", "desc"));
            case PRICE_ASC -> sort.add(Map.of("price", "asc"));
            case PRICE_DESC -> sort.add(Map.of("price", "desc"));
            case START_DATE -> { }
        }
        sort.add(Map.of("startDate", "asc"));
        sort.add(Map.of("travelId", "asc"));
        return sort;
    }

    /**
     * Suggestions while typing. The prefix fields hold every word's beginnings ("kay", "kaya", ...), so a
     * plain match on them is a fast prefix lookup. Matching titles, destinations, countries and activities
     * are taken from the best travels found; exact words come first, then prefixes, then typo matches.
     */
    public List<Suggestion> autocomplete(String text, int limit) {
        Map<String, Object> request = Map.of(
                "size", 20,
                "_source", List.of("travelId", "title", "startDate", "endDate", "destinations", "activities"),
                "query", Map.of("bool", Map.of(
                        "filter", List.of(Map.of("term", Map.of("bookable", true)),
                                Map.of("range", Map.of("startDate", Map.of("gt", "now/d")))),
                        "minimum_should_match", 1,
                        "should", List.of(
                                Map.of("multi_match", Map.of("query", text, "type", "cross_fields", "operator", "and",
                                        "fields", PREFIX_FIELDS, "boost", 3)),
                                Map.of("multi_match", Map.of("query", text, "fuzziness", "AUTO", "prefix_length", 1,
                                        "operator", "and", "fields", List.of("title", "destinations.name",
                                                "destinations.country", "activities.name")))))),
                "sort", List.of(Map.of("_score", "desc"), Map.of("startDate", "asc"), Map.of("travelId", "asc")));
        JsonNode answer = index.search(request);

        String typed = fold(text);
        Map<String, Suggestion> exact = new LinkedHashMap<>();
        Map<String, Suggestion> prefix = new LinkedHashMap<>();
        Map<String, Suggestion> fuzzy = new LinkedHashMap<>();
        for (JsonNode hit : answer.path("hits").path("hits")) {
            TravelDocument travel = JSON.convertValue(hit.path("_source"), TravelDocument.class);
            String firstStop = travel.destinations().isEmpty() ? "" : travel.destinations().getFirst().name();
            String dates = travel.startDate() + " to " + travel.endDate();
            Suggestion forTravel = new Suggestion(travel.title(), SuggestionType.TRAVEL, travel.travelId(),
                    firstStop.isEmpty() ? dates : dates + " · " + firstStop);
            boolean placed = place(typed, travel.title(), "T:" + travel.travelId(), forTravel, exact, prefix);
            for (TravelDocument.Place destination : travel.destinations()) {
                placed |= place(typed, destination.name(), "D:" + fold(destination.name()), new Suggestion(destination.name(),
                        SuggestionType.DESTINATION, null, "Destination in " + destination.country()), exact, prefix);
                placed |= place(typed, destination.country(), "C:" + fold(destination.country()), new Suggestion(
                        destination.country(), SuggestionType.COUNTRY, null, "Country"), exact, prefix);
            }
            for (TravelDocument.ActivityText activity : travel.activities())
                placed |= place(typed, activity.name(), "A:" + fold(activity.name()), new Suggestion(activity.name(),
                        SuggestionType.ACTIVITY, null, "Activity on " + travel.title()), exact, prefix);
            if (!placed) fuzzy.putIfAbsent("T:" + travel.travelId(), forTravel);
        }
        List<Suggestion> ordered = new ArrayList<>(exact.values());
        prefix.values().forEach(ordered::add);
        fuzzy.values().forEach(ordered::add);
        return ordered.stream().limit(limit).toList();
    }

    /** Files a suggestion as exact (whole text equal) or prefix (text or one of its words starts with the input). */
    private static boolean place(String typed, String candidate, String key, Suggestion suggestion,
                                 Map<String, Suggestion> exact, Map<String, Suggestion> prefix) {
        if (candidate == null || candidate.isBlank()) return false;
        String folded = fold(candidate);
        if (folded.equals(typed)) {
            exact.putIfAbsent(key, suggestion);
            prefix.remove(key);
            return true;
        }
        if (folded.startsWith(typed) || (" " + folded).contains(" " + typed)) {
            if (!exact.containsKey(key)) prefix.putIfAbsent(key, suggestion);
            return true;
        }
        return false;
    }

    /** Lower case without accents, the same folding the index analyzers apply ("Chaniá" becomes "chania"). */
    static String fold(String value) {
        String decomposed = Normalizer.normalize(value.trim(), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    public enum Sort { RELEVANCE, START_DATE, PRICE_ASC, PRICE_DESC }

    public enum SuggestionType { TRAVEL, DESTINATION, COUNTRY, ACTIVITY }

    /** A validated search request; {@code text} is null for "everything". */
    public record SearchQuery(String text, String country, String transportMode, LocalDate startFrom, LocalDate startTo,
                              BigDecimal minPrice, BigDecimal maxPrice, String currency, Sort sort, int page, int size) {}

    public record Suggestion(String text, SuggestionType type, UUID travelId, String context) {}

    public record HitPage(List<TravelHit> items, int page, int size, long totalElements, int totalPages,
                          Instant indexedAt) {}

    public record TravelHit(UUID travelId, String title, String description, List<String> destinations,
                            List<String> countries, LocalDate startDate, LocalDate endDate, Integer durationDays,
                            BigDecimal price, String currency, Integer remainingPlaces, UUID managerId,
                            String managerName, List<String> activities, List<String> transportModes,
                            List<String> highlights) {
        static TravelHit of(TravelDocument travel, List<String> highlights) {
            return new TravelHit(travel.travelId(), travel.title(), travel.description(),
                    travel.destinations().stream().map(TravelDocument.Place::name).toList(),
                    travel.destinations().stream().map(TravelDocument.Place::country).distinct().toList(),
                    travel.startDate(), travel.endDate(), travel.durationDays(), travel.price(), travel.currency(),
                    travel.remainingCapacity(), travel.managerId(), travel.managerName(),
                    travel.activities().stream().map(TravelDocument.ActivityText::name).toList(),
                    travel.transports().stream().map(TravelDocument.TransportText::mode).distinct().toList(),
                    List.copyOf(highlights));
        }
    }
}
