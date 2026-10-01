package com.travelplan.auth.security;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TokenSessionStore {
    private final JdbcTemplate jdbc;
    private final Map<UUID, Session> memory = new ConcurrentHashMap<>();

    @Autowired
    public TokenSessionStore(ObjectProvider<JdbcTemplate> provider) {
        this(provider.getIfAvailable());
    }

    public TokenSessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void recordIssued(UUID jti, String subject, Instant expiresAt) {
        if (jdbc != null) {
            jdbc.update("INSERT INTO admin_sessions (jti, subject, expires_at) VALUES (?, ?, ?)",
                    jti, subject, Timestamp.from(expiresAt));
        } else {
            memory.put(jti, new Session(subject, expiresAt, false));
        }
    }

    public boolean isActive(UUID jti, Instant now) {
        if (jdbc != null) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM admin_sessions WHERE jti=? AND revoked_at IS NULL AND expires_at > ?",
                    Integer.class, jti, Timestamp.from(now));
            return count != null && count > 0;
        }
        Session session = memory.get(jti);
        return session != null && !session.revoked() && session.expiresAt().isAfter(now);
    }

    public void revoke(UUID jti, Instant now) {
        if (jdbc != null) {
            jdbc.update("UPDATE admin_sessions SET revoked_at=? WHERE jti=? AND revoked_at IS NULL", Timestamp.from(now), jti);
        } else {
            memory.computeIfPresent(jti, (id, session) -> new Session(session.subject(), session.expiresAt(), true));
        }
    }

    public void revokeSubject(String subject, Instant now) {
        if (jdbc != null) {
            jdbc.update("UPDATE admin_sessions SET revoked_at=? WHERE subject=? AND revoked_at IS NULL", Timestamp.from(now), subject);
        } else {
            memory.replaceAll((id, session) -> subject.equals(session.subject())
                    ? new Session(session.subject(), session.expiresAt(), true) : session);
        }
    }

    private record Session(String subject, Instant expiresAt, boolean revoked) {}
}
