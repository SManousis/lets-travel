package com.travelplan.users;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class UserInternalController {
    private final UserStore store;
    private final byte[] serviceToken;

    UserInternalController(UserStore store,
            @Value("${security.internal-service-token:change-me-internal}") String serviceToken) {
        this.store = store;
        this.serviceToken = serviceToken.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/internal/users/{id}/payment-eligibility")
    ResponseEntity<Void> paymentEligibility(@PathVariable UUID id,
            @RequestHeader(value = "X-Internal-Service-Token", required = false) String presented) {
        if (presented == null || !MessageDigest.isEqual(serviceToken, presented.getBytes(StandardCharsets.UTF_8)))
            return ResponseEntity.status(403).build();
        try {
            return store.activeForPayment(id) ? ResponseEntity.noContent().build()
                    : ResponseEntity.status(409).build();
        } catch (UserStore.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Void> unavailable() { return ResponseEntity.status(503).build(); }
}
