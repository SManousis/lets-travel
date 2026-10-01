package com.travelplan.auth;

import com.travelplan.auth.security.TokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalTokenValidationController {
    private final TokenService tokens;
    private final String serviceToken;

    public InternalTokenValidationController(TokenService tokens,
            @Value("${security.internal-service-token:change-me-internal}") String serviceToken) {
        this.tokens = tokens;
        this.serviceToken = serviceToken;
    }

    @PostMapping("/internal/token-validation")
    ResponseEntity<ValidationResponse> validate(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Internal-Service-Token", required = false) String presented) {
        if (!serviceToken.equals(presented)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        TokenService.Claims claims = tokens.verify(authorization.substring(7));
        if (claims == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(new ValidationResponse(claims.subject(), claims.role(), claims.expiresAt()));
    }

    record ValidationResponse(String subject, String role, long expiresAt) {}
}
