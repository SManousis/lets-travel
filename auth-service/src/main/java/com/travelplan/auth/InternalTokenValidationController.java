package com.travelplan.auth;

import com.travelplan.auth.security.TokenService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Lets other services confirm that an access token's session is still active. */
@RestController
public class InternalTokenValidationController {
    private final TokenService tokens;
    private final byte[] serviceToken;

    public InternalTokenValidationController(TokenService tokens,
            @Value("${security.internal-service-token:change-me-internal}") String serviceToken) {
        this.tokens = tokens;
        this.serviceToken = serviceToken.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/internal/token-validation")
    ResponseEntity<ValidationResponse> validate(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Internal-Service-Token", required = false) String presented) {
        if (presented == null || !MessageDigest.isEqual(serviceToken, presented.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return tokens.verify(authorization.substring(7))
                .map(token -> ResponseEntity.ok(new ValidationResponse(token.userId(), token.role().name(), token.expiresAt().getEpochSecond())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    record ValidationResponse(UUID subject, String role, long expiresAt) {}
}
