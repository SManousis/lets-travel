package com.travelplan.auth.security;

import static org.junit.jupiter.api.Assertions.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import java.util.UUID;

class TokenServiceTest {
    private static final String SECRET = "01234567890123456789012345678901";

    @Test void issuedTokenVerifiesAndTamperingFails() {
        TokenService service = new TokenService(SECRET, 60);
        TokenService.IssuedToken issued = service.issue("admin", "ADMIN");
        assertEquals("ADMIN", service.verify(issued.value()).role());
        String tampered = "A" + issued.value().substring(1);
        assertNull(service.verify(tampered));
    }

    @Test void logoutRevokesExistingToken() {
        TokenService service = new TokenService(SECRET, 60);
        String token = service.issue("admin", "ADMIN").value();
        assertNotNull(service.verify(token));
        service.revoke(token);
        assertNull(service.verify(token));
    }

    @Test void expiredTokenIsRejected() {
        TokenService service = new TokenService(SECRET, 0);
        assertNull(service.verify(service.issue("admin", "ADMIN").value()));
    }

    @Test void disablingSubjectRevokesAllIssuedTokens() {
        TokenService service = new TokenService(SECRET, 60);
        String token = service.issue("user-1", "ADMIN").value();
        service.revokeSubject("user-1");
        assertNull(service.verify(token));
    }

    @Test void revocationIsVisibleToAnotherInstanceUsingTheSameDatabase() {
        JdbcTemplate jdbc = sessionDatabase();
        TokenService first = new TokenService(SECRET, 60, new TokenSessionStore(jdbc));
        String token = first.issue("admin", "ADMIN").value();
        TokenService second = new TokenService(SECRET, 60, new TokenSessionStore(jdbc));

        assertNotNull(second.verify(token));
        second.revoke(token);
        assertNull(first.verify(token));
    }

    @Test void subjectRevocationSurvivesNewServiceInstance() {
        JdbcTemplate jdbc = sessionDatabase();
        TokenService first = new TokenService(SECRET, 60, new TokenSessionStore(jdbc));
        String token = first.issue("user-1", "ADMIN").value();
        new TokenService(SECRET, 60, new TokenSessionStore(jdbc)).revokeSubject("user-1");

        assertNull(new TokenService(SECRET, 60, new TokenSessionStore(jdbc)).verify(token));
    }

    @Test void sessionStoreFailureIsNotReportedAsInvalidCredentials() {
        TokenSessionStore sessions = mock(TokenSessionStore.class);
        TokenService service = new TokenService(SECRET, 60, sessions);
        String token = service.issue("admin", "ADMIN").value();
        when(sessions.isActive(any(), any())).thenThrow(new IllegalStateException("database offline"));

        assertThrows(IllegalStateException.class, () -> service.verify(token));
    }

    @Test void changedIdentityInvalidatesEvenANewlyIssuedOldRoleToken() {
        var identities = new ProvisionedIdentityStore((JdbcTemplate) null);
        UUID subject = UUID.randomUUID();
        identities.provision(subject, "person@example.com", "hash", "ADMIN");
        TokenService service = new TokenService(SECRET, 60,
                new TokenSessionStore((JdbcTemplate) null), identities);
        String oldRoleToken = service.issue(subject.toString(), "ADMIN").value();

        identities.updateSubject(subject, "person@example.com", "OPERATOR", true);
        assertNull(service.verify(oldRoleToken));
        assertNull(service.verify(service.issue(subject.toString(), "ADMIN").value()));
    }

    private JdbcTemplate sessionDatabase() {
        var datasource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(datasource);
        jdbc.execute("CREATE TABLE admin_sessions (jti UUID PRIMARY KEY, subject VARCHAR(255) NOT NULL, expires_at TIMESTAMP WITH TIME ZONE NOT NULL, revoked_at TIMESTAMP WITH TIME ZONE)");
        return jdbc;
    }
}
