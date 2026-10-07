package com.travelplan.search.api;

import com.travelplan.search.index.SearchUnavailable;
import com.travelplan.search.index.TravelServiceClient;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps failures to the platform error body {code, message, fieldErrors, traceId}. */
@RestControllerAdvice
class SearchErrors {
    private static final Logger log = LoggerFactory.getLogger(SearchErrors.class);

    @ExceptionHandler(InvalidQuery.class)
    ResponseEntity<Error> invalid(InvalidQuery invalid, HttpServletResponse response) {
        return body(400, "INVALID_QUERY", "Search parameters are not valid", invalid.problems, response);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Error> mismatch(MethodArgumentTypeMismatchException mismatch, HttpServletResponse response) {
        return body(400, "INVALID_QUERY", "Search parameters are not valid",
                List.of(new FieldError(mismatch.getName(), "INVALID_VALUE", mismatch.getName() + " has an invalid value")), response);
    }

    /** Elasticsearch is down: a safe 503. Clients fall back to browsing travels (GET /api/travels). */
    @ExceptionHandler(SearchUnavailable.class)
    ResponseEntity<Error> unavailable(SearchUnavailable failure, HttpServletResponse response) {
        log.warn("Search unavailable: {}", failure.getMessage());
        response.setHeader("Retry-After", "30");
        return body(503, "SEARCH_UNAVAILABLE", "Search is temporarily unavailable. Browse the travel list instead.",
                List.of(), response);
    }

    @ExceptionHandler(TravelServiceClient.TravelServiceUnavailable.class)
    ResponseEntity<Error> travelServiceDown(HttpServletResponse response) {
        return body(503, "DEPENDENCY_UNAVAILABLE", "Required service unavailable", List.of(), response);
    }

    private static ResponseEntity<Error> body(int status, String code, String message, List<FieldError> fields,
                                              HttpServletResponse response) {
        String traceId = response.getHeader("X-Request-ID");
        return ResponseEntity.status(status).body(new Error(code, message, fields, traceId == null ? "" : traceId));
    }

    static final class InvalidQuery extends RuntimeException {
        final transient List<FieldError> problems;

        InvalidQuery(List<FieldError> problems) {
            super("Invalid search query");
            this.problems = List.copyOf(problems);
        }
    }

    record FieldError(String field, String code, String message) {}

    record Error(String code, String message, List<FieldError> fieldErrors, String traceId) {}
}
