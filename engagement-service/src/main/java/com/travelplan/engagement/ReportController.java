package com.travelplan.engagement;

import com.travelplan.common.security.AccessToken;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Filing reports (any traveler) and the admin moderation queue (docs/api/engagement.yaml). */
@RestController
class ReportController {
    private final ReportService reports;
    private final ReportStore store;

    ReportController(ReportService reports, ReportStore store) {
        this.reports = reports;
        this.store = store;
    }

    @PostMapping("/api/reports")
    ResponseEntity<ReportView> file(@AuthenticationPrincipal AccessToken caller,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                    @Valid @RequestBody ReportCreate body) {
        UUID idempotencyKey = SubscriptionController.idempotencyKey(key);
        ReportView view = view(reports.file(caller, body.targetType(), body.targetId(), body.category(),
                body.description(), idempotencyKey));
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /** The caller's own reports, including what they wrote. Never anyone else's. */
    @GetMapping("/api/reports")
    SubscriptionController.PageView<ReportView> mine(@AuthenticationPrincipal AccessToken caller,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        SubscriptionController.checkPage(page, size);
        ReportStore.Page found = reports.mine(caller, page, size);
        return SubscriptionController.PageView.of(found.items().stream().map(ReportController::view).toList(),
                page, size, found.total());
    }

    @GetMapping("/api/admin/reports")
    SubscriptionController.PageView<ReportDetail> queue(@AuthenticationPrincipal AccessToken caller,
                                                        @RequestParam(required = false) ReportStore.Status status,
                                                        @RequestParam(required = false) ReportStore.TargetType targetType,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        SubscriptionController.checkPage(page, size);
        ReportStore.Page found = reports.queue(caller, status, targetType, page, size);
        return SubscriptionController.PageView.of(found.items().stream().map(this::detail).toList(), page, size, found.total());
    }

    @GetMapping("/api/admin/reports/{reportId}")
    ReportDetail get(@AuthenticationPrincipal AccessToken caller, @PathVariable UUID reportId) {
        return detail(reports.get(caller, reportId));
    }

    @PatchMapping("/api/admin/reports/{reportId}")
    ReportDetail moderate(@AuthenticationPrincipal AccessToken caller, @PathVariable UUID reportId,
                          @Valid @RequestBody ModerationUpdate body) {
        return detail(reports.moderate(caller, reportId, body.status(), Boolean.TRUE.equals(body.assignToSelf()), body.note()));
    }

    private ReportDetail detail(ReportStore.Report report) {
        return new ReportDetail(report.reportId(), report.reporterId(), report.targetType(), report.targetId(),
                report.targetDisplayName(), report.category(), report.description(), report.status(), report.assigneeId(),
                store.history(report.reportId()), report.createdAt());
    }

    private static ReportView view(ReportStore.Report report) {
        return new ReportView(report.reportId(), report.targetType(), report.targetId(), report.targetDisplayName(),
                report.category(), report.description(), report.status(), report.createdAt());
    }

    record ReportCreate(@NotNull ReportStore.TargetType targetType, @NotNull UUID targetId,
                        @NotNull ReportStore.Category category,
                        @NotBlank @Size(min = 10, max = 1000) String description) {}

    record ModerationUpdate(@NotNull ReportStore.Status status, Boolean assignToSelf,
                            @NotBlank @Size(min = 3, max = 1000) String note) {}

    /** What the reporter sees about their own report. */
    record ReportView(UUID reportId, ReportStore.TargetType targetType, UUID targetId, String targetDisplayName,
                      ReportStore.Category category, String description, ReportStore.Status status, Instant createdAt) {}

    /** Admin-only view with the reporter and the moderation history. */
    record ReportDetail(UUID reportId, UUID reporterId, ReportStore.TargetType targetType, UUID targetId,
                        String targetDisplayName, ReportStore.Category category, String description,
                        ReportStore.Status status, UUID assigneeId, List<ReportStore.ModerationEntry> history,
                        Instant createdAt) {}
}
