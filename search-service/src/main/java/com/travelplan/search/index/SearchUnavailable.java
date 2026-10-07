package com.travelplan.search.index;

/** Elasticsearch (or the index) cannot answer right now; the API turns this into 503 SEARCH_UNAVAILABLE. */
public class SearchUnavailable extends RuntimeException {
    public SearchUnavailable(String message, Throwable cause) {
        super(message, cause);
    }
}
