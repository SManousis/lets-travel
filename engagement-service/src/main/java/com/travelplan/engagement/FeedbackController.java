package com.travelplan.engagement;

import com.travelplan.common.security.AccessToken;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Traveler feedback and the manager/admin quality-control view (docs/api/engagement.yaml). */
@RestController
class FeedbackController {
    private final FeedbackService feedback;

    FeedbackController(FeedbackService feedback) {
        this.feedback = feedback;
    }

    @PostMapping("/api/feedback")
    ResponseEntity<FeedbackView> submit(@AuthenticationPrincipal AccessToken caller,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                        @Valid @RequestBody FeedbackCreate body) {
        UUID idempotencyKey = SubscriptionController.idempotencyKey(key);
        FeedbackView view = view(feedback.submit(caller, body.travelId(), body.rating(), body.comment(), idempotencyKey));
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /** TRAVEL_MANAGER and above; the service narrows managers to their own travels. */
    @GetMapping("/api/feedback")
    SubscriptionController.PageView<FeedbackView> list(@AuthenticationPrincipal AccessToken caller,
                                                       @RequestParam(required = false) UUID travelId,
                                                       @RequestParam(required = false) UUID managerId,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        SubscriptionController.checkPage(page, size);
        return page(feedback.list(caller, travelId, managerId, page, size), page, size);
    }

    /** The caller's own feedback history. */
    @GetMapping("/api/feedback/mine")
    SubscriptionController.PageView<FeedbackView> mine(@AuthenticationPrincipal AccessToken caller,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        SubscriptionController.checkPage(page, size);
        return page(feedback.mine(caller, page, size), page, size);
    }

    private static SubscriptionController.PageView<FeedbackView> page(FeedbackStore.Page found, int page, int size) {
        return SubscriptionController.PageView.of(found.items().stream().map(FeedbackController::view).toList(),
                page, size, found.total());
    }

    private static FeedbackView view(FeedbackStore.Feedback item) {
        return new FeedbackView(item.feedbackId(), item.travelId(), item.travelTitle(), item.managerId(),
                item.authorDisplayName(), item.rating(), item.comment(), item.createdAt());
    }

    record FeedbackCreate(@NotNull UUID travelId, @NotNull @Min(1) @Max(5) Integer rating,
                          @Size(max = 1000) String comment) {}

    record FeedbackView(UUID feedbackId, UUID travelId, String travelTitle, UUID managerId, String authorDisplayName,
                        int rating, String comment, Instant createdAt) {}
}
