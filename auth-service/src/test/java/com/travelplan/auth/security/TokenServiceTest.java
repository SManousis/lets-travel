package com.travelplan.auth.security;

import com.travelplan.common.security.AccessToken;
import com.travelplan.common.security.AccessTokenCodec;
import com.travelplan.common.security.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TokenServiceTest {
    private static final String SECRET = "01234567890123456789012345678901";
    private static final Duration REFRESH_TTL = Duration.ofHours(12);

    private final ProvisionedIdentityStore identities = new ProvisionedIdentityStore((JdbcTemplate) null);
    private final AuthSessionStore sessions = new AuthSessionStore((JdbcTemplate) null);
    private final TokenService tokens = service(sessions, identities, Clock.systemUTC());
    private final ProvisionedIdentityStore.Identity traveler = provision("traveler@example.com", Role.TRAVELER);

    @Test void sessionTokenCarriesTheIdentityAndVerifies() {
        TokenService.Tokens session = tokens.startSession(traveler);

        AccessToken token = tokens.verify(session.access().value()).orElseThrow();
        assertThat(token.userId()).isEqualTo(traveler.subject());
        assertThat(token.role()).isEqualTo(Role.TRAVELER);
        assertThat(session.refreshToken()).isNotBlank().isNotEqualTo(session.csrfToken());
    }

    @Test void endingTheSessionRejectsItsAccessToken() {
        TokenService.Tokens session = tokens.startSession(traveler);
        tokens.endSession(tokens.verify(session.access().value()).orElseThrow().sessionId());
        assertThat(tokens.verify(session.access().value())).isEmpty();
    }

    @Test void revokingTheSubjectEndsEverySession() {
        String first = tokens.startSession(traveler).access().value();
        String second = tokens.startSession(traveler).access().value();
        tokens.revokeSubject(traveler.subject());
        assertThat(tokens.verify(first)).isEmpty();
        assertThat(tokens.verify(second)).isEmpty();
    }

    @Test void roleChangeOrDisablingRejectsExistingTokens() {
        String token = tokens.startSession(traveler).access().value();
        identities.updateSubject(traveler.subject(), traveler.username(), Role.TRAVEL_MANAGER, true);
        assertThat(tokens.verify(token)).isEmpty();

        ProvisionedIdentityStore.Identity manager = identities.findBySubject(traveler.subject()).orElseThrow();
        String managerToken = tokens.startSession(manager).access().value();
        identities.disableSubject(manager.subject());
        assertThat(tokens.verify(managerToken)).isEmpty();
    }

    @Test void refreshRotatesTokensAndInvalidatesTheOldRefreshToken() {
        TokenService.Tokens login = tokens.startSession(traveler);

        TokenService.Tokens rotated = tokens.refresh(login.refreshToken(), login.csrfToken()).orElseThrow();
        assertThat(rotated.refreshToken()).isNotEqualTo(login.refreshToken());
        assertThat(rotated.csrfToken()).isNotEqualTo(login.csrfToken());
        assertThat(tokens.verify(rotated.access().value())).isPresent();
        assertThat(tokens.refresh(rotated.refreshToken(), rotated.csrfToken())).isPresent();
    }

    @Test void reusingARotatedRefreshTokenEndsTheSession() {
        TokenService.Tokens login = tokens.startSession(traveler);
        TokenService.Tokens rotated = tokens.refresh(login.refreshToken(), login.csrfToken()).orElseThrow();

        assertThat(tokens.refresh(login.refreshToken(), login.csrfToken())).isEmpty();
        assertThat(tokens.verify(rotated.access().value())).isEmpty();
        assertThat(tokens.refresh(rotated.refreshToken(), rotated.csrfToken())).isEmpty();
    }

    @Test void refreshNeedsTheMatchingCsrfToken() {
        TokenService.Tokens login = tokens.startSession(traveler);
        assertThat(tokens.refresh(login.refreshToken(), "wrong")).isEmpty();
        assertThat(tokens.refresh(login.refreshToken(), null)).isEmpty();
        assertThat(tokens.refresh(null, login.csrfToken())).isEmpty();
        assertThat(tokens.refresh(login.refreshToken(), login.csrfToken())).as("a CSRF failure does not end the session").isPresent();
    }

    @Test void refreshFailsAfterTheSessionLifetime() {
        Instant start = Instant.parse("2026-10-06T12:00:00Z");
        var lateSessions = new AuthSessionStore((JdbcTemplate) null);
        TokenService.Tokens login = service(lateSessions, identities, Clock.fixed(start, ZoneOffset.UTC)).startSession(traveler);
        TokenService later = service(lateSessions, identities, Clock.fixed(start.plus(REFRESH_TTL).plusSeconds(1), ZoneOffset.UTC));
        assertThat(later.refresh(login.refreshToken(), login.csrfToken())).isEmpty();
    }

    @Test void refreshUsesTheCurrentRoleAndStopsForDisabledIdentities() {
        TokenService.Tokens login = tokens.startSession(traveler);
        identities.updateSubject(traveler.subject(), traveler.username(), Role.TRAVEL_MANAGER, true);
        TokenService.Tokens promoted = tokens.refresh(login.refreshToken(), login.csrfToken()).orElseThrow();
        assertThat(tokens.verify(promoted.access().value()).orElseThrow().role()).isEqualTo(Role.TRAVEL_MANAGER);

        identities.disableSubject(traveler.subject());
        assertThat(tokens.refresh(promoted.refreshToken(), promoted.csrfToken())).isEmpty();
    }

    @Test void sessionsAreSharedThroughTheDatabaseAndStoredOnlyAsHashes() {
        JdbcTemplate jdbc = migratedDatabase();
        var sharedIdentities = new ProvisionedIdentityStore(jdbc);
        UUID id = UUID.randomUUID();
        sharedIdentities.provision(id, "manager@example.com", "hash", Role.TRAVEL_MANAGER);
        TokenService first = service(new AuthSessionStore(jdbc), sharedIdentities, Clock.systemUTC());
        TokenService second = service(new AuthSessionStore(jdbc), new ProvisionedIdentityStore(jdbc), Clock.systemUTC());

        TokenService.Tokens login = first.startSession(sharedIdentities.findByUsername("manager@example.com"));
        assertThat(second.verify(login.access().value())).isPresent();
        String stored = jdbc.queryForObject("SELECT refresh_token_hash || csrf_token_hash FROM auth_sessions", String.class);
        assertThat(stored).doesNotContain(login.refreshToken()).doesNotContain(login.csrfToken());

        second.revokeSubject(id);
        assertThat(first.verify(login.access().value())).isEmpty();
    }

    @Test void sessionStoreFailureIsNotReportedAsInvalidCredentials() {
        AuthSessionStore failing = mock(AuthSessionStore.class);
        TokenService service = service(failing, identities, Clock.systemUTC());
        String token = service.startSession(traveler).access().value();
        when(failing.isActive(any(), any())).thenThrow(new IllegalStateException("database offline"));

        assertThatThrownBy(() -> service.verify(token)).isInstanceOf(IllegalStateException.class);
    }

    private ProvisionedIdentityStore.Identity provision(String username, Role role) {
        identities.provision(UUID.randomUUID(), username, "hash", role);
        return identities.findByUsername(username);
    }

    private static TokenService service(AuthSessionStore sessions, ProvisionedIdentityStore identities, Clock clock) {
        return new TokenService(new AccessTokenCodec(SECRET, Duration.ofMinutes(15), clock), sessions, identities, REFRESH_TTL, clock);
    }

    static JdbcTemplate migratedDatabase() {
        return new JdbcTemplate(migratedDataSource());
    }

    static DriverManagerDataSource migratedDataSource() {
        var datasource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new JdbcTemplate(datasource).execute("CREATE DOMAIN TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE");
        Flyway.configure().dataSource(datasource).locations("classpath:db/migration").load().migrate();
        return datasource;
    }
}
