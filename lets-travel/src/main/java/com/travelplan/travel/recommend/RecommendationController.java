package com.travelplan.travel.recommend;

import com.travelplan.common.security.AccessToken;
import com.travelplan.travel.recommend.RecommendationEngine.Recommendation;
import com.travelplan.travel.recommend.RecommendationEngine.Recommendations;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Personalized recommendations (docs/api/recommendations.yaml) and their event intake and operations. */
@RestController
class RecommendationController {
    private final RecommendationService recommendations;
    private final HistoryReconciler reconciler;

    RecommendationController(RecommendationService recommendations, HistoryReconciler reconciler) {
        this.recommendations = recommendations;
        this.reconciler = reconciler;
    }

    @GetMapping("/api/recommendations")
    ResponseEntity<?> recommend(@AuthenticationPrincipal AccessToken caller, @RequestParam(defaultValue = "10") int limit) {
        if (limit < 1 || limit > 20) {
            return ResponseEntity.badRequest().body(new Error("VALIDATION_ERROR", "limit must be between 1 and 20"));
        }
        return ResponseEntity.ok(view(recommendations.recommend(caller.userId(), limit)));
    }

    /** engagement-service delivers its outbox events here; the internal service token is checked by the shared filter. */
    @PostMapping("/internal/events")
    RecommendationService.EventResult events(@RequestBody List<RecommendationService.Event> events) {
        return recommendations.apply(events);
    }

    @GetMapping("/api/admin/recommendations/status")
    RecommendationService.Status status() {
        return recommendations.status();
    }

    /** Runs the reconciliation now instead of waiting for the schedule. */
    @PostMapping("/api/admin/recommendations/reconcile")
    RecommendationService.ReconcileStatus reconcile() {
        return reconciler.reconcileNow();
    }

    @ExceptionHandler(RecommendationService.Unavailable.class)
    ResponseEntity<Error> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new Error("DEPENDENCY_UNAVAILABLE", "Recommendations are temporarily unavailable"));
    }

    private static View view(Recommendations result) {
        return new View(result.strategy().name(), result.items().stream().map(RecommendationController::item).toList());
    }

    private static Item item(Recommendation recommendation) {
        TravelFeatures travel = recommendation.travel();
        return new Item(travel.travelId(), travel.title(), travel.startDate(), travel.endDate(), money(travel.price(), travel.currency()),
                recommendation.score(), recommendation.reasons().stream()
                        .map(reason -> new ReasonView(reason.field().name(), reason.value())).toList());
    }

    private static Money money(BigDecimal price, String currency) {
        if (price == null || currency == null) return null;
        int digits = Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
        return new Money(price.movePointRight(digits).longValueExact(), currency);
    }

    record View(String strategy, List<Item> items) {}

    record Item(UUID travelId, String title, LocalDate startDate, LocalDate endDate, Money price, double score, List<ReasonView> reasons) {}

    record ReasonView(String field, String value) {}

    record Money(long amountMinor, String currency) {}

    record Error(String code, String message, List<Object> fieldErrors, String traceId) {
        Error(String code, String message) {
            this(code, message, List.of(), "");
        }
    }
}
