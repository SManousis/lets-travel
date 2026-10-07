package com.travelplan.search.api;

import com.travelplan.search.api.TravelSearch.HitPage;
import com.travelplan.search.api.TravelSearch.SearchQuery;
import com.travelplan.search.api.TravelSearch.Sort;
import com.travelplan.search.api.TravelSearch.Suggestion;
import com.travelplan.search.index.TravelIndexer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Search API (contract: docs/api/search.yaml). Every parameter is checked here before anything is sent
 * to Elasticsearch: lengths and value ranges are bounded and free text may not contain control characters.
 */
@RestController
class SearchController {
    static final int MAX_QUERY_LENGTH = 100;
    static final int MAX_PAGE_SIZE = 50;
    /** Deep paging is expensive in Elasticsearch; the API stops at the first 1,000 results. */
    static final int MAX_RESULT_WINDOW = 1000;
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{M} .'-]{2,60}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private final TravelSearch search;
    private final TravelIndexer indexer;

    SearchController(TravelSearch search, TravelIndexer indexer) {
        this.search = search;
        this.indexer = indexer;
    }

    @GetMapping("/api/search/travels")
    HitPage searchTravels(@RequestParam(required = false) String q,
                          @RequestParam(required = false) String country,
                          @RequestParam(required = false) String transportMode,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startFrom,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startTo,
                          @RequestParam(required = false) BigDecimal minPrice,
                          @RequestParam(required = false) BigDecimal maxPrice,
                          @RequestParam(required = false) String currency,
                          @RequestParam(required = false) Sort sort,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "20") int size) {
        List<SearchErrors.FieldError> problems = new ArrayList<>();
        String text = text(q, "q", 0, MAX_QUERY_LENGTH, problems);
        String countryName = name(country, "country", problems);
        String mode = name(transportMode, "transportMode", problems);
        if (startFrom != null && startTo != null && startTo.isBefore(startFrom))
            problems.add(new SearchErrors.FieldError("startTo", "INVALID_RANGE", "startTo must not be before startFrom"));
        if (minPrice != null && minPrice.signum() < 0)
            problems.add(new SearchErrors.FieldError("minPrice", "INVALID_VALUE", "minPrice must be 0 or more"));
        if (maxPrice != null && maxPrice.signum() < 0)
            problems.add(new SearchErrors.FieldError("maxPrice", "INVALID_VALUE", "maxPrice must be 0 or more"));
        if (minPrice != null && maxPrice != null && maxPrice.compareTo(minPrice) < 0)
            problems.add(new SearchErrors.FieldError("maxPrice", "INVALID_RANGE", "maxPrice must not be below minPrice"));
        if (currency != null && !CURRENCY.matcher(currency).matches())
            problems.add(new SearchErrors.FieldError("currency", "INVALID_VALUE", "currency must be an ISO 4217 code such as EUR"));
        if ((minPrice != null || maxPrice != null) && currency == null)
            problems.add(new SearchErrors.FieldError("currency", "REQUIRED", "currency is required with a price filter"));
        if (page < 0) problems.add(new SearchErrors.FieldError("page", "INVALID_VALUE", "page must be 0 or more"));
        if (size < 1 || size > MAX_PAGE_SIZE)
            problems.add(new SearchErrors.FieldError("size", "INVALID_VALUE", "size must be between 1 and " + MAX_PAGE_SIZE));
        else if (page >= 0 && (long) (page + 1) * size > MAX_RESULT_WINDOW)
            problems.add(new SearchErrors.FieldError("page", "TOO_DEEP", "Only the first " + MAX_RESULT_WINDOW + " results can be paged"));
        if (!problems.isEmpty()) throw new SearchErrors.InvalidQuery(problems);

        Sort order = sort != null ? sort : text != null ? Sort.RELEVANCE : Sort.START_DATE;
        return search.search(new SearchQuery(text, countryName, mode, startFrom, startTo, minPrice, maxPrice, currency,
                order, page, size));
    }

    @GetMapping("/api/search/autocomplete")
    Suggestions autocomplete(@RequestParam(required = false) String q, @RequestParam(defaultValue = "8") int limit) {
        List<SearchErrors.FieldError> problems = new ArrayList<>();
        String text = text(q, "q", 2, 64, problems);
        if (text == null && problems.isEmpty())
            problems.add(new SearchErrors.FieldError("q", "REQUIRED", "Type at least 2 characters"));
        if (limit < 1 || limit > 10)
            problems.add(new SearchErrors.FieldError("limit", "INVALID_VALUE", "limit must be between 1 and 10"));
        if (!problems.isEmpty()) throw new SearchErrors.InvalidQuery(problems);
        return new Suggestions(search.autocomplete(text, limit));
    }

    /** Operational view: index lag (queued changes), failing changes and the last rebuild. */
    @GetMapping("/api/search/admin/status")
    TravelIndexer.Status status() {
        return indexer.status();
    }

    /** Rebuilds the index from travel-service now and switches the alias; searches keep working meanwhile. */
    @PostMapping("/api/search/admin/reindex")
    TravelIndexer.Rebuild reindex() {
        return indexer.rebuild();
    }

    /** travel-service reports a committed change. Only the ID is sent; the current state is fetched back. */
    @PostMapping("/internal/search/index/travels/{travelId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void travelChanged(@PathVariable UUID travelId) {
        indexer.travelChanged(travelId);
    }

    /** Trimmed text, null when empty; refuses control characters and out-of-range lengths. */
    private static String text(String raw, String field, int min, int max, List<SearchErrors.FieldError> problems) {
        if (raw == null) return null;
        String trimmed = raw.strip().replaceAll("\\s+", " ");
        if (CONTROL.matcher(raw).find()) {
            problems.add(new SearchErrors.FieldError(field, "INVALID_CHARACTERS", field + " contains control characters"));
            return null;
        }
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() < min || trimmed.length() > max) {
            problems.add(new SearchErrors.FieldError(field, "INVALID_LENGTH",
                    field + " must be " + min + " to " + max + " characters"));
            return null;
        }
        return trimmed;
    }

    private static String name(String raw, String field, List<SearchErrors.FieldError> problems) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.strip();
        if (!NAME.matcher(trimmed).matches()) {
            problems.add(new SearchErrors.FieldError(field, "INVALID_VALUE", field + " must be 2 to 60 letters"));
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    record Suggestions(List<Suggestion> items) {}
}
