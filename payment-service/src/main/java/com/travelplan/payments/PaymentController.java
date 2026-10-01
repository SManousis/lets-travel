package com.travelplan.payments;

import com.travelplan.payments.PaymentDomain.Conflict;
import com.travelplan.payments.PaymentDomain.Gateway;
import com.travelplan.payments.PaymentDomain.InvalidRequest;
import com.travelplan.payments.PaymentDomain.Method;
import com.travelplan.payments.PaymentDomain.NotFound;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class PaymentController {
    private final PaymentStore store;
    private final Map<String, ProviderAdapter> adapters;
    private final PaymentDeletionService deletion;
    private final PaymentUserClient users;

    public PaymentController(PaymentStore store, List<ProviderAdapter> providers,
                             PaymentDeletionService deletion, PaymentUserClient users) {
        this.store = store;
        this.deletion = deletion;
        this.users = users;
        this.adapters = new HashMap<>();
        providers.forEach(adapter -> adapters.put(adapter.provider(), adapter));
    }

    @GetMapping("/api/payment-gateways")
    List<Gateway> gateways() { return store.gateways(); }

    @PostMapping("/api/payment-gateways")
    ResponseEntity<Gateway> createGateway(@Valid @RequestBody GatewayWrite body) {
        String provider = provider(body.provider());
        return ResponseEntity.status(HttpStatus.CREATED).body(store.createGateway(provider, body.displayName()));
    }

    @GetMapping("/api/payment-gateways/{id}")
    Gateway gateway(@PathVariable UUID id) { return store.gateway(id); }

    @PatchMapping("/api/payment-gateways/{id}")
    Gateway updateGateway(@PathVariable UUID id, @Valid @RequestBody GatewayWrite body) {
        return store.updateGateway(id, provider(body.provider()), body.displayName());
    }

    @DeleteMapping("/api/payment-gateways/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteGateway(@PathVariable UUID id) { store.deleteGateway(id); }

    @GetMapping("/api/payment-methods")
    List<MethodView> methods(@RequestParam(required = false) UUID userId) {
        return store.methods(userId).stream().map(PaymentController::view).toList();
    }

    @PostMapping("/api/payment-methods")
    ResponseEntity<MethodView> createMethod(@Valid @RequestBody MethodWrite body,
                                            @RequestHeader("Idempotency-Key") UUID key) {
        String idempotencyKey = key.toString();
        String hash = requestHash(body);
        Method previous = store.existingForKey(idempotencyKey, hash);
        try {
            users.requireActive(body.userId());
        } catch (NotFound | Conflict ex) {
            if (previous == null) throw ex;
            return ResponseEntity.status("PENDING".equals(previous.status()) ? HttpStatus.ACCEPTED : HttpStatus.OK)
                    .body(view(previous));
        }
        var attempt = store.beginConnect(body.userId(), body.gatewayId(), body.providerReference(),
                body.displayLabel(), idempotencyKey, hash, Boolean.TRUE.equals(body.isDefault()));
        if (!attempt.shouldInspect()) return ResponseEntity.status("PENDING".equals(attempt.method().status())
                ? HttpStatus.ACCEPTED : HttpStatus.OK).body(view(attempt.method()));
        ProviderAdapter adapter = adapter(attempt.method().provider());
        try {
            var result = adapter.connect(attempt.method().providerReference(), idempotencyKey);
            Method connected = store.completeConnect(attempt.method(), result.displayMetadata());
            return ResponseEntity.status(attempt.created() ? HttpStatus.CREATED : HttpStatus.OK).body(view(connected));
        } catch (ProviderAdapter.Rejected ex) {
            store.failConnect(attempt.method(), ex.code);
            throw ex;
        } catch (ProviderAdapter.Unavailable ex) {
            store.failConnect(attempt.method(), ex.code);
            throw ex;
        }
    }

    @GetMapping("/api/payment-methods/{id}")
    MethodView method(@PathVariable UUID id) { return view(store.method(id)); }

    @PatchMapping("/api/payment-methods/{id}")
    MethodView updateMethod(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        if (body.isEmpty() || body.keySet().stream()
                .anyMatch(field -> !"displayLabel".equals(field) && !"isDefault".equals(field)))
            throw new InvalidRequest();
        String label = null;
        Boolean isDefault = null;
        if (body.containsKey("displayLabel")) {
            Object value = body.get("displayLabel");
            if (!(value instanceof String text) || text.isBlank() || text.length() > 120)
                throw new InvalidRequest();
            label = text;
        }
        if (body.containsKey("isDefault")) {
            if (!(body.get("isDefault") instanceof Boolean value)) throw new InvalidRequest();
            isDefault = value;
        }
        return view(store.updatePreferences(id, label, isDefault));
    }

    @DeleteMapping("/api/payment-methods/{id}")
    ResponseEntity<Void> deleteMethod(@PathVariable UUID id, @RequestHeader("Idempotency-Key") UUID key) {
        return deletion.delete(id, key) ? ResponseEntity.noContent().build() : ResponseEntity.accepted().build();
    }

    private ProviderAdapter adapter(String provider) {
        ProviderAdapter result = adapters.get(provider);
        if (result == null) throw new Conflict("UNSUPPORTED_PROVIDER");
        return result;
    }

    private String provider(String value) {
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!adapters.containsKey(normalized)) throw new Conflict("UNSUPPORTED_PROVIDER");
        return normalized;
    }

    private static MethodView view(Method method) {
        return new MethodView(method.id(), method.userId(), method.gatewayId(), method.provider(),
                method.displayLabel(), method.isDefault(), method.status(), method.displayMetadata(),
                method.failureCode(), method.createdAt());
    }

    private static String requestHash(MethodWrite body) {
        String canonical = canonicalField(body.userId().toString()) + canonicalField(body.gatewayId().toString())
                + canonicalField(body.providerReference()) + canonicalField(body.displayLabel())
                + (Boolean.TRUE.equals(body.isDefault()) ? canonicalField("true") : "");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String canonicalField(String value) { return value.length() + ":" + value; }

    @ExceptionHandler(NotFound.class)
    ResponseEntity<Error> missing() {
        return ResponseEntity.status(404).body(new Error("NOT_FOUND", "Resource not found"));
    }

    @ExceptionHandler(InvalidRequest.class)
    ResponseEntity<Error> invalidRequest() {
        return ResponseEntity.badRequest().body(new Error("VALIDATION_ERROR", "Invalid payment method patch"));
    }

    @ExceptionHandler(Conflict.class)
    ResponseEntity<Error> conflict(Conflict error) {
        return ResponseEntity.status(409).body(new Error(error.code, "Request conflicts with current state"));
    }

    @ExceptionHandler(ProviderAdapter.Rejected.class)
    ResponseEntity<Error> providerRejected(ProviderAdapter.Rejected error) {
        return ResponseEntity.status(409).body(new Error(error.code, "Provider rejected the reference"));
    }

    @ExceptionHandler({ProviderAdapter.Unavailable.class, DataAccessException.class})
    ResponseEntity<Error> unavailable(RuntimeException error) {
        String code = error instanceof ProviderAdapter.Unavailable provider ? provider.code : "PAYMENT_STORE_UNAVAILABLE";
        return ResponseEntity.status(503).body(new Error(code, "Payment dependency unavailable"));
    }

    record GatewayWrite(@NotBlank String provider, @NotBlank @Size(max = 200) String displayName) {}
    record MethodWrite(@NotNull UUID userId, @NotNull UUID gatewayId,
                       @NotBlank @Size(max = 255) String providerReference,
                       @NotBlank @Size(max = 120) String displayLabel, Boolean isDefault) {}
    record MethodView(UUID id, UUID userId, UUID gatewayId, String provider, String displayLabel,
                      boolean isDefault, String status, String displayMetadata, String failureCode,
                      Instant createdAt) {}
    record Error(String code, String message) {}
}
