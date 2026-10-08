package com.travelplan.payments;

import com.travelplan.common.audit.AuditLog;
import com.travelplan.common.security.AccessToken;
import com.travelplan.common.security.Role;
import com.travelplan.payments.SubscriptionPayment.State;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Traveler subscription payments (docs/api/payments.yaml, docs/payments.md), the signed provider
 * webhooks, and the private refund hand-off from engagement-service.
 */
@RestController
class SubscriptionPaymentController {
    private static final Logger log = LoggerFactory.getLogger(SubscriptionPaymentController.class);
    private final SubscriptionPaymentService service;

    private final AuditLog audit;

    SubscriptionPaymentController(SubscriptionPaymentService service, AuditLog audit) {
        this.audit = audit;
        this.service = service;
    }

    /** 201 for a new payment, 200 when the same Idempotency-Key is replayed. The body carries the state. */
    @PostMapping("/api/subscription-payments")
    ResponseEntity<PaymentView> pay(@AuthenticationPrincipal AccessToken caller,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                    @Valid @RequestBody PayBody body) {
        String idempotencyKey = idempotencyKey(key);
        boolean saved = body.paymentMethodId() != null;
        boolean test = body.testOutcome() != null;
        if (saved == test)
            throw new SubscriptionPaymentService.Failure(400, "VALIDATION_ERROR", "Choose either a saved payment method or the test provider");
        if (test && !SubscriptionPaymentService.TEST_OUTCOMES.contains(body.testOutcome()))
            throw new SubscriptionPaymentService.Failure(400, "VALIDATION_ERROR", "testOutcome must be APPROVE, DECLINE or TIMEOUT");
        var result = service.pay(caller.userId(), new SubscriptionPaymentService.PayRequest(body.subscriptionId(),
                body.paymentMethodId(), body.testOutcome()), idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view(result.payment()));
    }

    @GetMapping("/api/subscription-payments")
    List<PaymentView> mine(@AuthenticationPrincipal AccessToken caller) {
        return service.mine(caller.userId()).stream().map(SubscriptionPaymentController::view).toList();
    }

    /** The owner, or an admin. Anyone else gets 404, the same as for an unknown payment. */
    @GetMapping("/api/subscription-payments/{paymentId}")
    PaymentView get(@AuthenticationPrincipal AccessToken caller, @PathVariable UUID paymentId) {
        return service.find(paymentId).filter(payment -> payment.userId().equals(caller.userId()) || caller.role() == Role.ADMIN)
                .map(SubscriptionPaymentController::view).orElseThrow(PaymentDomain.NotFound::new);
    }

    /** What the caller can pay with: their own ACTIVE saved methods (safe display data only) and the test provider. */
    @GetMapping("/api/payment-options")
    OptionsView options(@AuthenticationPrincipal AccessToken caller) {
        var options = service.options(caller.userId());
        return new OptionsView(options.methods().stream().map(method -> new MethodOption(method.id(), method.provider(),
                method.displayLabel(), method.displayMetadata(), method.isDefault(),
                options.providerReady().getOrDefault(method.provider(), false))).toList(), options.testProviderEnabled());
    }

    /** Public: the caller is the provider. Trust comes only from the verified signature header. */
    @PostMapping("/api/payment-webhooks/{provider}")
    Map<String, Boolean> webhook(@PathVariable String provider,
                                 @RequestHeader(value = "Stripe-Signature", required = false) String stripeSignature,
                                 @RequestHeader(value = "Test-Signature", required = false) String testSignature,
                                 @RequestBody(required = false) String body) {
        service.webhook(provider, "stripe".equals(provider) ? stripeSignature : testSignature, body == null ? "" : body);
        return Map.of("received", true);
    }

    /** engagement-service: a paid subscription was cancelled. 200 with the payment, 204 if nothing was paid. */
    @PostMapping("/internal/subscriptions/{subscriptionId}/refund")
    ResponseEntity<PaymentView> refund(@PathVariable UUID subscriptionId) {
        return service.refundForSubscription(subscriptionId).map(payment -> {
            audit.record("REFUND_REQUESTED", "PAYMENT", payment.paymentId(), Map.of("subscriptionId", subscriptionId,
                    "amountMinor", payment.amountMinor(), "currency", payment.currency(), "state", payment.state()));
            return ResponseEntity.ok(view(payment));
        }).orElseGet(() -> ResponseEntity.noContent().build());
    }

    private static String idempotencyKey(String raw) {
        try {
            if (raw != null) return UUID.fromString(raw.trim()).toString();
        } catch (IllegalArgumentException ignored) {
            // Reported below.
        }
        throw new SubscriptionPaymentService.Failure(400, "VALIDATION_ERROR", "Idempotency-Key header must be a UUID");
    }

    static PaymentView view(SubscriptionPayment payment) {
        return new PaymentView(payment.paymentId(), payment.subscriptionId(), payment.amountMinor(), payment.currency(),
                payment.provider(), payment.methodDisplay(), payment.state(), payment.failureCode(), message(payment),
                payment.refundReason(), payment.createdAt(), payment.updatedAt());
    }

    /** Safe, actionable text for the traveler; never a provider's own error message. */
    static String message(SubscriptionPayment payment) {
        return switch (payment.state()) {
            case PROCESSING -> "Your payment is being processed.";
            case REQUIRES_RECONCILIATION -> "We are confirming this payment with the provider. You will not be charged twice; "
                    + "check again in a few minutes.";
            case SUCCEEDED -> payment.outcomeDelivered() ? "Payment received. Your place is confirmed."
                    : "Payment received. Confirming your place…";
            case FAILED -> switch (payment.failureCode() == null ? "" : payment.failureCode()) {
                case "PAYMENT_DECLINED" -> "The payment was declined, so your reserved place was released. "
                        + "You can subscribe again and use another payment method.";
                case "AUTHENTICATION_REQUIRED" -> "Your bank asked for extra authentication, which is not possible here. "
                        + "Your reserved place was released; subscribe again with another payment method.";
                default -> "The payment could not be completed and your reserved place was released. "
                        + "You can subscribe again with another payment method.";
            };
            case REFUND_PENDING -> "A full refund is being processed.";
            case REFUNDED -> "This payment was refunded in full.";
        };
    }

    record PayBody(@NotNull UUID subscriptionId, UUID paymentMethodId, String testOutcome) {}

    record PaymentView(UUID paymentId, UUID subscriptionId, long amountMinor, String currency, String provider,
                       String methodDisplay, State state, String failureCode, String message, String refundReason,
                       Instant createdAt, Instant updatedAt) {}

    record MethodOption(UUID id, String provider, String displayLabel, String displayMetadata, boolean isDefault,
                        boolean available) {}

    record OptionsView(List<MethodOption> methods, boolean testProviderEnabled) {}

    record FieldError(String field, String code, String message) {}

    record Error(String code, String message, List<FieldError> fieldErrors, String traceId) {}

    @ExceptionHandler(SubscriptionPaymentService.Failure.class)
    ResponseEntity<Error> failure(SubscriptionPaymentService.Failure failure, HttpServletResponse response) {
        return error(failure.status, failure.code, failure.getMessage(), List.of(), response);
    }

    @ExceptionHandler(PaymentDomain.NotFound.class)
    ResponseEntity<Error> notFound(HttpServletResponse response) {
        return error(404, "NOT_FOUND", "Not found", List.of(), response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Error> invalid(MethodArgumentNotValidException invalid, HttpServletResponse response) {
        List<FieldError> fields = invalid.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), "INVALID", error.getDefaultMessage())).toList();
        return error(400, "VALIDATION_ERROR", "Request validation failed", fields, response);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Error> unreadable(HttpServletResponse response) {
        return error(400, "VALIDATION_ERROR", "Request validation failed", List.of(), response);
    }

    @ExceptionHandler({SubscriptionPaymentService.DependencyUnavailable.class, ProviderAdapter.Unavailable.class,
            PaymentDomain.Conflict.class})
    ResponseEntity<Error> unavailable(HttpServletResponse response) {
        return error(503, "DEPENDENCY_UNAVAILABLE", "A required service is unavailable. Nothing was charged; try again.",
                List.of(), response);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Error> database(DataAccessException failure, HttpServletResponse response) {
        log.warn("Payment database unavailable: {}", failure.getClass().getSimpleName());
        return error(503, "DEPENDENCY_UNAVAILABLE", "A required service is unavailable. Try again.", List.of(), response);
    }

    private static ResponseEntity<Error> error(int status, String code, String message, List<FieldError> fields,
                                               HttpServletResponse response) {
        String traceId = response.getHeader("X-Request-ID");
        return ResponseEntity.status(status).body(new Error(code, message, fields, traceId == null ? "" : traceId));
    }
}
