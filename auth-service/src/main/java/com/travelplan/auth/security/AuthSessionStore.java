package com.travelplan.auth.security;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * Login sessions. Refresh and CSRF tokens are stored only as SHA-256 hashes. Without a
 * database (local runs and unit tests) sessions live in memory.
 */
@Component
public class AuthSessionStore {
    private static final String COLUMNS = "session_id, subject, refresh_token_hash, previous_refresh_token_hash, csrf_token_hash, expires_at, revoked_at";
    private static final RowMapper<Session> ROWS = (rs, row) -> new Session(rs.getObject("session_id", UUID.class),
            rs.getObject("subject", UUID.class), rs.getString("refresh_token_hash"), rs.getString("previous_refresh_token_hash"),
            rs.getString("csrf_token_hash"), rs.getTimestamp("expires_at").toInstant(),
            rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant());

    private final JdbcTemplate jdbc;
    private final Map<UUID, Session> memory = new ConcurrentHashMap<>();

    @Autowired
    public AuthSessionStore(ObjectProvider<JdbcTemplate> provider) {
        this(provider.getIfAvailable());
    }

    public AuthSessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void create(Session session, Instant now) {
        if (jdbc == null) {
            memory.put(session.id(), session);
            return;
        }
        jdbc.update("INSERT INTO auth_sessions (session_id, subject, refresh_token_hash, csrf_token_hash, created_at, expires_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)", session.id(), session.subject(), session.refreshHash(),
                session.csrfHash(), Timestamp.from(now), Timestamp.from(session.expiresAt()));
    }

    public boolean isActive(UUID sessionId, Instant now) {
        return find(sessionId).filter(session -> session.activeAt(now)).isPresent();
    }

    public Optional<Session> find(UUID sessionId) {
        if (jdbc == null) return Optional.ofNullable(memory.get(sessionId));
        return jdbc.query("SELECT " + COLUMNS + " FROM auth_sessions WHERE session_id = ?", ROWS, sessionId).stream().findFirst();
    }

    /** Finds the session whose current refresh token has this hash, locking it for rotation. */
    public Optional<Session> findByRefreshHashForUpdate(String refreshHash) {
        if (jdbc == null) return memory.values().stream().filter(s -> s.refreshHash().equals(refreshHash)).findFirst();
        return jdbc.query("SELECT " + COLUMNS + " FROM auth_sessions WHERE refresh_token_hash = ? FOR UPDATE", ROWS, refreshHash)
                .stream().findFirst();
    }

    /** Finds the session whose previous (already rotated) refresh token has this hash. */
    public Optional<Session> findByPreviousRefreshHash(String refreshHash) {
        if (jdbc == null) return memory.values().stream().filter(s -> refreshHash.equals(s.previousRefreshHash())).findFirst();
        return jdbc.query("SELECT " + COLUMNS + " FROM auth_sessions WHERE previous_refresh_token_hash = ?", ROWS, refreshHash)
                .stream().findFirst();
    }

    public void rotate(UUID sessionId, String oldRefreshHash, String newRefreshHash, String newCsrfHash) {
        if (jdbc == null) {
            memory.computeIfPresent(sessionId, (id, s) -> new Session(id, s.subject(), newRefreshHash, oldRefreshHash,
                    newCsrfHash, s.expiresAt(), s.revokedAt()));
            return;
        }
        jdbc.update("UPDATE auth_sessions SET refresh_token_hash = ?, previous_refresh_token_hash = ?, csrf_token_hash = ?"
                + " WHERE session_id = ?", newRefreshHash, oldRefreshHash, newCsrfHash, sessionId);
    }

    public void revoke(UUID sessionId, Instant now) {
        if (jdbc == null) {
            memory.computeIfPresent(sessionId, (id, s) -> s.revokedAt() != null ? s : s.revoked(now));
            return;
        }
        jdbc.update("UPDATE auth_sessions SET revoked_at = ? WHERE session_id = ? AND revoked_at IS NULL", Timestamp.from(now), sessionId);
    }

    public void revokeSubject(UUID subject, Instant now) {
        if (jdbc == null) {
            memory.replaceAll((id, s) -> s.subject().equals(subject) && s.revokedAt() == null ? s.revoked(now) : s);
            return;
        }
        jdbc.update("UPDATE auth_sessions SET revoked_at = ? WHERE subject = ? AND revoked_at IS NULL", Timestamp.from(now), subject);
    }

    public record Session(UUID id, UUID subject, String refreshHash, String previousRefreshHash, String csrfHash,
                          Instant expiresAt, Instant revokedAt) {
        boolean activeAt(Instant now) {
            return revokedAt == null && expiresAt.isAfter(now);
        }

        Session revoked(Instant now) {
            return new Session(id, subject, refreshHash, previousRefreshHash, csrfHash, expiresAt, now);
        }
    }
}
