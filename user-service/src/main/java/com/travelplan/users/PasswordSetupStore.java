package com.travelplan.users;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-time password-setup tokens. The raw token is returned once and only its SHA-256
 * hash is stored. Issuing a new token for a user invalidates that user's earlier ones.
 */
@Component
public class PasswordSetupStore {
    static final Duration LIFETIME = Duration.ofHours(72);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> memory = new ConcurrentHashMap<>();

    @Autowired
    public PasswordSetupStore(ObjectProvider<JdbcTemplate> provider) {
        this(provider.getIfAvailable(), Clock.systemUTC());
    }

    PasswordSetupStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Issued issue(UUID userId) {
        Instant now = clock.instant();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = now.plus(LIFETIME);
        if (jdbc == null) {
            memory.replaceAll((hash, entry) -> entry.userId().equals(userId) && entry.usedAt() == null ? entry.used(now) : entry);
            memory.put(hash(token), new Entry(userId, expiresAt, null));
        } else {
            jdbc.update("UPDATE password_setup_tokens SET used_at = ? WHERE user_id = ? AND used_at IS NULL", Timestamp.from(now), userId);
            jdbc.update("INSERT INTO password_setup_tokens (token_hash, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
                    hash(token), userId, Timestamp.from(now), Timestamp.from(expiresAt));
        }
        return new Issued(token, expiresAt);
    }

    /** The user a still-valid token belongs to; locks the token row until the transaction ends. */
    public Optional<UUID> findValidForUpdate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        Instant now = clock.instant();
        if (jdbc == null) {
            Entry entry = memory.get(hash(token));
            return entry != null && entry.usedAt() == null && entry.expiresAt().isAfter(now) ? Optional.of(entry.userId()) : Optional.empty();
        }
        return jdbc.query("SELECT user_id FROM password_setup_tokens WHERE token_hash = ? AND used_at IS NULL AND expires_at > ? FOR UPDATE",
                (rs, row) -> rs.getObject("user_id", UUID.class), hash(token), Timestamp.from(now)).stream().findFirst();
    }

    public void markUsed(String token) {
        Instant now = clock.instant();
        if (jdbc == null) {
            memory.computeIfPresent(hash(token), (hash, entry) -> entry.used(now));
            return;
        }
        jdbc.update("UPDATE password_setup_tokens SET used_at = ? WHERE token_hash = ?", Timestamp.from(now), hash(token));
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public record Issued(String token, Instant expiresAt) {}

    private record Entry(UUID userId, Instant expiresAt, Instant usedAt) {
        Entry used(Instant at) {
            return new Entry(userId, expiresAt, at);
        }
    }
}
