package com.travelplan.payments;

import com.travelplan.payments.PaymentDomain.Conflict;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class PaymentCleanupController {
    private final PaymentStore store;
    private final PaymentDeletionService deletion;
    private final byte[] serviceToken;

    PaymentCleanupController(PaymentStore store, PaymentDeletionService deletion,
            @Value("${security.internal-service-token:change-me-internal}") String serviceToken) {
        this.store = store;
        this.deletion = deletion;
        this.serviceToken = serviceToken.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/internal/users/{userId}/payment-cleanup")
    ResponseEntity<Void> cleanup(@PathVariable UUID userId,
            @RequestHeader(value = "X-Internal-Service-Token", required = false) String presented) {
        if (presented == null || !MessageDigest.isEqual(serviceToken, presented.getBytes(StandardCharsets.UTF_8)))
            return ResponseEntity.status(403).build();
        store.startUserCleanup(userId);
        // At most two provider calls per request keep this synchronous endpoint below its caller's timeout.
        for (var method : store.methods(userId).stream()
                .sorted(java.util.Comparator.comparing(m -> "FAILED".equals(m.status())))
                .limit(2).toList()) {
            UUID key = UUID.nameUUIDFromBytes(("payment-cleanup:" + method.id()).getBytes(StandardCharsets.UTF_8));
            try {
                deletion.delete(method.id(), key);
            } catch (ProviderAdapter.Rejected | ProviderAdapter.Unavailable | Conflict
                     | PaymentDomain.NotFound ex) {
                // The final collection read determines whether cleanup is complete.
            }
        }
        return store.methods(userId).isEmpty() ? ResponseEntity.noContent().build()
                : ResponseEntity.accepted().build();
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(DataAccessException.class)
    ResponseEntity<Void> unavailable() { return ResponseEntity.status(503).build(); }
}
