package com.travelplan.common.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessTokenCodecTest {
    private static final String SECRET = "a-test-secret-that-is-at-least-32-bytes-long";
    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AccessTokenCodec codec = new AccessTokenCodec(SECRET, Duration.ofMinutes(15), clock);
    private final UUID user = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();

    @Test void issuedTokenRoundTripsWithEveryClaim() {
        AccessTokenCodec.Issued issued = codec.issue(user, Role.TRAVEL_MANAGER, session);

        AccessToken token = codec.verify(issued.value()).orElseThrow();
        assertThat(token.userId()).isEqualTo(user);
        assertThat(token.role()).isEqualTo(Role.TRAVEL_MANAGER);
        assertThat(token.sessionId()).isEqualTo(session);
        assertThat(token.tokenId()).isNotNull();
        assertThat(token.issuedAt()).isEqualTo(NOW);
        assertThat(token.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15))).isEqualTo(issued.expiresAt());
        assertThat(token.isUser(user)).isTrue();
    }

    @Test void issuedTokenIsAStandardHs256Jwt() {
        String[] parts = codec.issue(user, Role.ADMIN, session).value().split("\\.");
        String header = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(parts).hasSize(3);
        assertThat(header).contains("\"alg\":\"HS256\"");
        assertThat(payload).contains("\"iss\":\"lets-travel-auth\"", "\"aud\":\"lets-travel-api\"", "\"role\":\"ADMIN\"",
                "\"sub\":\"" + user + "\"", "\"sid\":\"" + session + "\"", "\"iat\"", "\"exp\"", "\"jti\"");
    }

    @Test void rejectsTamperedSignatureAndPayload() {
        String token = codec.issue(user, Role.TRAVELER, session).value();
        String[] parts = token.split("\\.");
        String elevated = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                        .replace("TRAVELER", "ADMIN").getBytes(StandardCharsets.UTF_8));

        assertThat(codec.verify(parts[0] + "." + elevated + "." + parts[2])).isEmpty();
        assertThat(codec.verify(parts[0] + "." + parts[1] + "." + parts[2].substring(0, parts[2].length() - 2) + "AA")).isEmpty();
    }

    @Test void rejectsTokenSignedWithAnotherKey() {
        String foreign = new AccessTokenCodec("another-secret-that-is-at-least-32-bytes", Duration.ofMinutes(15), clock)
                .issue(user, Role.ADMIN, session).value();
        assertThat(codec.verify(foreign)).isEmpty();
    }

    @Test void rejectsExpiredTokenAfterClockSkew() {
        String token = codec.issue(user, Role.TRAVELER, session).value();
        AccessTokenCodec later = new AccessTokenCodec(SECRET, Duration.ofMinutes(15),
                Clock.fixed(NOW.plus(Duration.ofMinutes(15)).plusSeconds(31), ZoneOffset.UTC));
        assertThat(later.verify(token)).isEmpty();
    }

    /** Control for the rejection tests below: the test signer's unmodified token is valid. */
    @Test void acceptsUnmodifiedTokenFromTestSigner() {
        assertThat(codec.verify(signed(MacAlgorithm.HS256, claims -> { }))).isPresent();
    }

    @Test void rejectsWrongIssuerOrAudience() {
        assertThat(codec.verify(signed(MacAlgorithm.HS256, claims -> claims.issuer("someone-else")))).isEmpty();
        assertThat(codec.verify(signed(MacAlgorithm.HS256, claims -> claims.audience(List.of("other-api"))))).isEmpty();
    }

    @Test void rejectsUnsupportedOrMissingRole() {
        assertThat(codec.verify(signed(MacAlgorithm.HS256, claims -> claims.claim("role", "OPERATOR")))).isEmpty();
        assertThat(codec.verify(signed(MacAlgorithm.HS256, claims -> claims.claims(map -> map.remove("role"))))).isEmpty();
    }

    @Test void rejectsMissingOrMalformedIdentifiers() {
        assertThat(codec.verify(signed(MacAlgorithm.HS256, claims -> claims.subject("admin")))).isEmpty();
        assertThat(codec.verify(signed(MacAlgorithm.HS256, claims -> claims.claims(map -> map.remove("sid"))))).isEmpty();
    }

    @Test void rejectsTokenIssuedInTheFuture() {
        assertThat(codec.verify(signed(MacAlgorithm.HS256, claims -> claims.issuedAt(NOW.plus(Duration.ofMinutes(5)))))).isEmpty();
    }

    @Test void rejectsOtherAlgorithmsIncludingNone() {
        assertThat(codec.verify(signed(MacAlgorithm.HS512, claims -> { }))).isEmpty();
        String valid = codec.issue(user, Role.ADMIN, session).value();
        String[] parts = valid.split("\\.");
        String none = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(codec.verify(none + "." + parts[1] + ".")).isEmpty();
    }

    @Test void rejectsBlankOrGarbageInput() {
        assertThat(codec.verify(null)).isEmpty();
        assertThat(codec.verify(" ")).isEmpty();
        assertThat(codec.verify("not-a-token")).isEmpty();
    }

    @Test void refusesShortSecrets() {
        assertThatThrownBy(() -> new AccessTokenCodec("too-short", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccessTokenCodec(null, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Signs a token like the codec does, after letting the test change one claim. */
    private String signed(MacAlgorithm algorithm, Consumer<JwtClaimsSet.Builder> change) {
        byte[] key = algorithm == MacAlgorithm.HS512
                ? (SECRET + SECRET).getBytes(StandardCharsets.UTF_8) : SECRET.getBytes(StandardCharsets.UTF_8);
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(key, algorithm.getName())));
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(AccessTokenCodec.ISSUER).audience(List.of(AccessTokenCodec.AUDIENCE))
                .subject(user.toString()).issuedAt(NOW).expiresAt(NOW.plus(Duration.ofMinutes(15)))
                .id(UUID.randomUUID().toString()).claim("role", "ADMIN").claim("sid", session.toString());
        change.accept(claims);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(algorithm).build(), claims.build())).getTokenValue();
    }
}
