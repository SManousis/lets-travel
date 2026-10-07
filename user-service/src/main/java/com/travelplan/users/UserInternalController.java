package com.travelplan.users;

import com.travelplan.common.security.Role;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
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

    @GetMapping("/internal/managers/{id}")
    ResponseEntity<ManagerSummary> manager(@PathVariable UUID id,
            @RequestHeader(value = "X-Internal-Service-Token", required = false) String presented) {
        if (!authorized(presented)) return ResponseEntity.status(403).build();
        try {
            UserDomain.User user = store.get(id);
            if (user.role() != Role.TRAVEL_MANAGER || user.status() != UserDomain.Status.ACTIVE)
                return ResponseEntity.status(409).build();
            return ResponseEntity.ok(new ManagerSummary(user.id(), displayName(user), user.createdAt()));
        } catch (UserStore.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * The minimum engagement-service needs about a subscriber: whether they may book, and a safe
     * public summary for the travel's manager. No email, passport or other contact details.
     */
    @GetMapping("/internal/users/{id}/summary")
    ResponseEntity<UserSummary> summary(@PathVariable UUID id,
            @RequestHeader(value = "X-Internal-Service-Token", required = false) String presented) {
        if (!authorized(presented)) return ResponseEntity.status(403).build();
        try {
            UserDomain.User user = store.get(id);
            return ResponseEntity.ok(new UserSummary(user.id(), displayName(user), user.createdAt(),
                    user.status() == UserDomain.Status.ACTIVE));
        } catch (UserStore.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
    }

    private boolean authorized(String presented) {
        return presented != null
                && MessageDigest.isEqual(serviceToken, presented.getBytes(StandardCharsets.UTF_8));
    }

    private static String displayName(UserDomain.User user) {
        return (user.firstName() + " " + user.lastName()).trim().replaceAll("\\s+", " ");
    }

    record ManagerSummary(UUID managerId, String displayName, Instant memberSince) {}
    record UserSummary(UUID userId, String displayName, Instant memberSince, boolean active) {}

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Void> unavailable() { return ResponseEntity.status(503).build(); }
}
