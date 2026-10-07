package com.travelplan.engagement;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps subscription failures to the platform error body {code, message, fieldErrors, traceId}. */
@RestControllerAdvice(assignableTypes = {SubscriptionController.class, SubscriptionInternalController.class,
        FeedbackController.class, ReportController.class})
class SubscriptionErrors {
    private static final Logger log = LoggerFactory.getLogger(SubscriptionErrors.class);

    @ExceptionHandler(SubscriptionService.Failure.class)
    ResponseEntity<Error> failure(SubscriptionService.Failure failure, HttpServletResponse response) {
        return body(failure.status, failure.code, failure.getMessage(), List.of(), response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Error> invalid(MethodArgumentNotValidException invalid, HttpServletResponse response) {
        List<FieldError> fields = invalid.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), "INVALID", error.getDefaultMessage())).toList();
        return body(400, "VALIDATION_ERROR", "Request validation failed", fields, response);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Error> unreadable(HttpServletResponse response) {
        return body(400, "VALIDATION_ERROR", "Request validation failed", List.of(), response);
    }

    @ExceptionHandler(ManagerPageService.DependencyUnavailable.class)
    ResponseEntity<Error> unavailable(HttpServletResponse response) {
        return body(503, "DEPENDENCY_UNAVAILABLE", "Required service unavailable", List.of(), response);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Error> database(DataAccessException failure, HttpServletResponse response) {
        log.warn("Subscription database unavailable: {}", failure.getClass().getSimpleName());
        return body(503, "DEPENDENCY_UNAVAILABLE", "Required service unavailable", List.of(), response);
    }

    private static ResponseEntity<Error> body(int status, String code, String message, List<FieldError> fields,
                                              HttpServletResponse response) {
        String traceId = response.getHeader("X-Request-ID");
        return ResponseEntity.status(status).body(new Error(code, message, fields, traceId == null ? "" : traceId));
    }

    record FieldError(String field, String code, String message) {}

    record Error(String code, String message, List<FieldError> fieldErrors, String traceId) {}
}
