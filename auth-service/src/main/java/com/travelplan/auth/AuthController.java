package com.travelplan.auth;

import com.travelplan.auth.security.IdentitySessionCoordinator;
import com.travelplan.auth.security.PasswordRules;
import com.travelplan.auth.security.ProvisionedIdentityStore;
import com.travelplan.auth.security.TokenService;
import com.travelplan.common.security.AccessToken;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public session endpoints. The access token travels in the JSON body and the
 * Authorization header; the refresh token only in a Secure HttpOnly cookie scoped to
 * /api/auth. Refresh additionally needs the CSRF token (readable cookie echoed in a
 * header) and an allowed Origin.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    static final String REFRESH_COOKIE = "refresh_token";
    static final String CSRF_COOKIE = "csrf_token";
    private static final Duration LOGIN_BACKOFF = Duration.ofSeconds(10);

    private final TokenService tokens;
    private final ProvisionedIdentityStore identities;
    private final IdentitySessionCoordinator coordinator;
    private final PasswordEncoder passwords;
    private final List<String> allowedOrigins;
    private final String unknownUserHash;
    private final Map<String, Instant> failedLogins = new ConcurrentHashMap<>();

    public AuthController(TokenService tokens, ProvisionedIdentityStore identities, IdentitySessionCoordinator coordinator,
                          PasswordEncoder passwords,
                          @Value("${security.allowed-origins:https://localhost:8443,http://localhost:5173,http://127.0.0.1:5173}") List<String> allowedOrigins) {
        this.tokens = tokens;
        this.identities = identities;
        this.coordinator = coordinator;
        this.passwords = passwords;
        this.allowedOrigins = allowedOrigins;
        this.unknownUserHash = passwords.encode(UUID.randomUUID().toString());
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        Instant blockedUntil = failedLogins.get(request.username());
        if (blockedUntil != null && blockedUntil.isAfter(Instant.now())) {
            return error(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Try again later");
        }
        return coordinator.execute(() -> {
            ProvisionedIdentityStore.Identity identity = identities.findByUsernameForUpdate(request.username());
            boolean usable = identity != null && identity.enabled() && PasswordRules.fits(request.password());
            // Hash even for unknown users so response time does not reveal which usernames exist.
            boolean matches = usable && passwords.matches(request.password(), identity.passwordHash());
            if (!usable) passwords.matches("", unknownUserHash);
            if (!usable || !matches) {
                failedLogins.put(request.username(), Instant.now().plus(LOGIN_BACKOFF));
                return error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Invalid credentials");
            }
            failedLogins.remove(request.username());
            if (passwords.upgradeEncoding(identity.passwordHash())) {
                // Upgrade path: a hash made with an older, cheaper setting is replaced now that the password is known.
                identities.updatePassword(identity.subject(), passwords.encode(request.password()));
            }
            return withSession(ResponseEntity.ok(), tokens.startSession(identity));
        });
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@CookieValue(value = REFRESH_COOKIE, required = false) String refreshToken,
                                     @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
                                     @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin) {
        if (origin == null || !allowedOrigins.contains(origin)) {
            return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "Origin not allowed");
        }
        return coordinator.execute(() -> tokens.refresh(refreshToken, csrfToken)
                .<ResponseEntity<?>>map(rotated -> withSession(ResponseEntity.ok(), rotated))
                .orElseGet(() -> error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Session expired")));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal AccessToken token) {
        tokens.endSession(token.sessionId());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
                .header(HttpHeaders.SET_COOKIE, csrfCookie("", Duration.ZERO).toString())
                .build();
    }

    @GetMapping("/me")
    public Me me(@AuthenticationPrincipal AccessToken token) {
        return new Me(token.userId(), token.role().name(), token.sessionId(), token.expiresAt().getEpochSecond());
    }

    private ResponseEntity<?> withSession(ResponseEntity.BodyBuilder response, TokenService.Tokens session) {
        Duration lifetime = Duration.between(Instant.now(), session.sessionExpiresAt());
        return response
                .header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken(), lifetime).toString())
                .header(HttpHeaders.SET_COOKIE, csrfCookie(session.csrfToken(), lifetime).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new TokenResponse(session.access().value(), "Bearer", session.access().expiresAt().getEpochSecond()));
    }

    private static ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value).httpOnly(true).secure(true).sameSite("Strict")
                .path("/api/auth").maxAge(maxAge).build();
    }

    /** Readable by the dashboard so it can echo the value in X-CSRF-Token. */
    private static ResponseCookie csrfCookie(String value, Duration maxAge) {
        return ResponseCookie.from(CSRF_COOKIE, value).httpOnly(false).secure(true).sameSite("Strict")
                .path("/").maxAge(maxAge).build();
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message, List.of(), ""));
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record TokenResponse(String accessToken, String tokenType, long expiresAt) {}
    public record Me(UUID userId, String role, UUID sessionId, long expiresAt) {}
    public record ErrorResponse(String code, String message, List<Object> fieldErrors, String traceId) {}
}
