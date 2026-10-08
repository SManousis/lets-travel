package com.travelplan.engagement;

import com.travelplan.common.audit.AuditLog;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Called by user-service while it deletes an account (internal service token, checked by the shared filter).
 * Repeating the call is safe. What is removed and what is kept, and why, is in docs/privacy.md.
 */
@RestController
class PersonalDataController {
    private final SubscriptionService subscriptions;
    private final FeedbackStore feedback;
    private final AuditLog audit;

    PersonalDataController(SubscriptionService subscriptions, FeedbackStore feedback, AuditLog audit) {
        this.subscriptions = subscriptions;
        this.feedback = feedback;
        this.audit = audit;
    }

    /** 204 when done; 202 while a refund still waits for payment-service (call again later). */
    @PostMapping("/internal/users/{userId}/personal-data-cleanup")
    ResponseEntity<Void> cleanup(@PathVariable UUID userId) {
        boolean settled = subscriptions.closeAccount(userId);
        int anonymized = feedback.anonymizeAuthor(userId);
        audit.record("ACCOUNT_DATA_CLEANED", "USER", userId, Map.of("feedbackAnonymized", anonymized, "settled", settled));
        return settled ? ResponseEntity.noContent().build() : ResponseEntity.accepted().build();
    }

    @ExceptionHandler(ManagerPageService.DependencyUnavailable.class)
    ResponseEntity<Error> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new Error("DEPENDENCY_UNAVAILABLE", "Engagement data is temporarily unavailable"));
    }

    record Error(String code, String message, List<Object> fieldErrors, String traceId) {
        Error(String code, String message) {
            this(code, message, List.of(), "");
        }
    }
}
