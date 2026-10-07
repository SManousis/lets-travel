package com.travelplan.common.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Issues and verifies HS256 JWT access tokens. Verification checks the algorithm,
 * signature, issuer, audience, expiry, issue time and the presence and format of every
 * claim the platform relies on. Session revocation is checked separately by auth-service.
 */
public final class AccessTokenCodec {
    public static final String ISSUER = "lets-travel-auth";
    public static final String AUDIENCE = "lets-travel-api";
    static final String ROLE_CLAIM = "role";
    static final String SESSION_CLAIM = "sid";
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private final NimbusJwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final Duration ttl;
    private final Clock clock;

    public AccessTokenCodec(String secret, Duration ttl, Clock clock) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("security.token-secret must contain at least 32 bytes");
        }
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(clock);
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(AUDIENCE));
        this.decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, new JwtIssuerValidator(ISSUER), audience));
        this.ttl = ttl;
        this.clock = clock;
    }

    public AccessTokenCodec(String secret, Duration ttl) {
        this(secret, ttl, Clock.systemUTC());
    }

    public Issued issue(UUID userId, Role role, UUID sessionId) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .id(UUID.randomUUID().toString())
                .claim(ROLE_CLAIM, role.name())
                .claim(SESSION_CLAIM, sessionId.toString())
                .build();
        Jwt jwt = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims));
        return new Issued(jwt.getTokenValue(), claims.getExpiresAt());
    }

    public Optional<AccessToken> verify(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        try {
            Jwt jwt = decoder.decode(token);
            Instant issuedAt = jwt.getIssuedAt();
            Instant expiresAt = jwt.getExpiresAt();
            if (issuedAt == null || expiresAt == null || issuedAt.isAfter(clock.instant().plus(CLOCK_SKEW))) {
                return Optional.empty();
            }
            Optional<Role> role = Role.parse(jwt.getClaimAsString(ROLE_CLAIM));
            if (role.isEmpty()) return Optional.empty();
            return Optional.of(new AccessToken(UUID.fromString(jwt.getSubject()), role.get(),
                    UUID.fromString(jwt.getClaimAsString(SESSION_CLAIM)), UUID.fromString(jwt.getId()), issuedAt, expiresAt));
        } catch (JwtException | IllegalArgumentException | NullPointerException ex) {
            return Optional.empty();
        }
    }

    public record Issued(String value, Instant expiresAt) {}
}
