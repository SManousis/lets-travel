package com.travelplan.auth;

import com.travelplan.auth.security.IdentitySessionCoordinator;
import com.travelplan.auth.security.ProvisionedIdentityStore;
import com.travelplan.auth.security.TokenService;
import com.travelplan.common.security.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identity commands from user-service. Served under /internal, which the gateway never
 * routes, and only with the internal service token.
 */
@RestController
public class InternalIdentityController {
    private final ProvisionedIdentityStore identities;
    private final TokenService tokens;
    private final IdentitySessionCoordinator coordinator;
    private final PasswordEncoder passwords;
    private final byte[] serviceToken;

    public InternalIdentityController(ProvisionedIdentityStore identities, TokenService tokens, IdentitySessionCoordinator coordinator,
                                      PasswordEncoder passwords,
                                      @Value("${security.internal-service-token:change-me-internal}") String serviceToken) {
        this.identities = identities;
        this.tokens = tokens;
        this.coordinator = coordinator;
        this.passwords = passwords;
        this.serviceToken = serviceToken.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/internal/identities")
    public ResponseEntity<Void> provision(@Valid @RequestBody ProvisionRequest request,
                                          @RequestHeader(value = "X-Internal-Service-Token", required = false) String presented) {
        if (!trusted(presented)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        Role role = Role.parse(request.role()).orElse(null);
        if (role == null) return ResponseEntity.badRequest().build();
        return coordinator.execute(() -> {
            if (identities.findByUsername(request.username()) != null) return ResponseEntity.status(HttpStatus.CONFLICT).build();
            identities.provision(request.userId(), request.username(), passwords.encode(request.password()), role);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        });
    }

    /** Changing the role or disabling ends every session of the identity immediately. */
    @PostMapping("/internal/identities/{subject}")
    public ResponseEntity<Void> update(@PathVariable UUID subject, @Valid @RequestBody UpdateRequest request,
                                       @RequestHeader(value = "X-Internal-Service-Token", required = false) String presented) {
        if (!trusted(presented)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        Role role = Role.parse(request.role()).orElse(null);
        if (role == null) return ResponseEntity.badRequest().build();
        return coordinator.execute(() -> {
            if (!identities.updateSubject(subject, request.username(), role, request.enabled())) return ResponseEntity.notFound().build();
            tokens.revokeSubject(subject);
            return ResponseEntity.noContent().build();
        });
    }

    @PostMapping("/internal/revoke/{subject}")
    public ResponseEntity<Void> revoke(@PathVariable UUID subject,
                                       @RequestHeader(value = "X-Internal-Service-Token", required = false) String presented) {
        if (!trusted(presented)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return coordinator.execute(() -> {
            identities.disableSubject(subject);
            tokens.revokeSubject(subject);
            return ResponseEntity.noContent().build();
        });
    }

    private boolean trusted(String presented) {
        return presented != null && MessageDigest.isEqual(serviceToken, presented.getBytes(StandardCharsets.UTF_8));
    }

    public record ProvisionRequest(@NotNull UUID userId, @NotBlank String username, @NotBlank String password, @NotBlank String role) {}
    public record UpdateRequest(@NotBlank String username, @NotBlank String role, boolean enabled) {}
}
