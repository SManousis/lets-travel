package com.travelplan.auth.security;

import com.travelplan.common.security.AccessToken;
import com.travelplan.common.security.AccessTokenCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Starts, checks, refreshes and ends login sessions. An access token is valid only while
 * its session is active and its identity is still enabled with the same role, so logout,
 * disabling and role changes take effect on the next request.
 */
@Service
public class TokenService {
    private final AccessTokenCodec codec;
    private final AuthSessionStore sessions;
    private final ProvisionedIdentityStore identities;
    private final Duration refreshTtl;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public TokenService(AccessTokenCodec codec, AuthSessionStore sessions, ProvisionedIdentityStore identities,
                        @Value("${security.refresh-token-ttl-seconds:43200}") long refreshTtlSeconds) {
        this(codec, sessions, identities, Duration.ofSeconds(refreshTtlSeconds), Clock.systemUTC());
    }

    public TokenService(AccessTokenCodec codec, AuthSessionStore sessions, ProvisionedIdentityStore identities,
                        Duration refreshTtl, Clock clock) {
        this.codec = codec;
        this.sessions = sessions;
        this.identities = identities;
        this.refreshTtl = refreshTtl;
        this.clock = clock;
    }

    /** Starts a session for an identity that has just proved its password. */
    public Tokens startSession(ProvisionedIdentityStore.Identity identity) {
        Instant now = clock.instant();
        String refresh = randomToken();
        String csrf = randomToken();
        UUID sessionId = UUID.randomUUID();
        Instant expiresAt = now.plus(refreshTtl);
        sessions.create(new AuthSessionStore.Session(sessionId, identity.subject(), hash(refresh), null, hash(csrf), expiresAt, null), now);
        return new Tokens(codec.issue(identity.subject(), identity.role(), sessionId), refresh, csrf, expiresAt);
    }

    public Optional<AccessToken> verify(String accessToken) {
        return codec.verify(accessToken)
                .filter(token -> sessions.isActive(token.sessionId(), clock.instant()))
                .filter(token -> identities.matchesCurrentRole(token.userId(), token.role()));
    }

    /**
     * Rotates the refresh token. Returns empty for an unknown, expired or revoked session, a
     * wrong CSRF token, or a disabled identity. Reusing an already rotated refresh token is
     * treated as theft and ends the session.
     */
    public Optional<Tokens> refresh(String refreshToken, String csrfToken) {
        if (refreshToken == null || csrfToken == null) return Optional.empty();
        Instant now = clock.instant();
        String refreshHash = hash(refreshToken);
        Optional<AuthSessionStore.Session> current = sessions.findByRefreshHashForUpdate(refreshHash);
        if (current.isEmpty()) {
            sessions.findByPreviousRefreshHash(refreshHash).ifPresent(reused -> sessions.revoke(reused.id(), now));
            return Optional.empty();
        }
        AuthSessionStore.Session session = current.get();
        if (!session.activeAt(now) || !sameHash(session.csrfHash(), hash(csrfToken))) return Optional.empty();
        Optional<ProvisionedIdentityStore.Identity> identity = identities.findBySubject(session.subject())
                .filter(ProvisionedIdentityStore.Identity::enabled);
        if (identity.isEmpty()) {
            sessions.revoke(session.id(), now);
            return Optional.empty();
        }
        String refresh = randomToken();
        String csrf = randomToken();
        sessions.rotate(session.id(), refreshHash, hash(refresh), hash(csrf));
        return Optional.of(new Tokens(codec.issue(session.subject(), identity.get().role(), session.id()),
                refresh, csrf, session.expiresAt()));
    }

    public void endSession(UUID sessionId) {
        sessions.revoke(sessionId, clock.instant());
    }

    public void revokeSubject(UUID subject) {
        sessions.revokeSubject(subject, clock.instant());
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static boolean sameHash(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    public record Tokens(AccessTokenCodec.Issued access, String refreshToken, String csrfToken, Instant sessionExpiresAt) {}
}
