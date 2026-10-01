package com.travelplan.auth.security;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ProvisionedIdentityStore {
    private final JdbcTemplate jdbc;
    private final Map<String, Identity> memory = new ConcurrentHashMap<>();

    @Autowired
    public ProvisionedIdentityStore(ObjectProvider<JdbcTemplate> provider) {
        this(provider.getIfAvailable());
    }

    public ProvisionedIdentityStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Identity findByUsername(String username) {
        return findByUsername(username, false);
    }

    public Identity findByUsernameForUpdate(String username) {
        return findByUsername(username, true);
    }

    private Identity findByUsername(String username, boolean lock) {
        if (jdbc == null) return memory.get(username);
        return jdbc.query("SELECT subject, username, password_hash, role, enabled FROM auth_identities WHERE username=?"
                        + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Identity(rs.getObject("subject", UUID.class), rs.getString("username"),
                        rs.getString("password_hash"), rs.getString("role"), rs.getBoolean("enabled")),
                username).stream().findFirst().orElse(null);
    }

    public boolean matchesCurrentRole(String subject, String role) {
        UUID id;
        try { id = UUID.fromString(subject); }
        catch (IllegalArgumentException ex) { return true; } // Bootstrap admin has a username subject.
        if (jdbc != null) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM auth_identities WHERE subject=? AND role=? AND enabled=TRUE",
                    Integer.class, id, role);
            return count != null && count == 1;
        }
        return memory.values().stream().anyMatch(identity -> identity.subject().equals(id)
                && identity.enabled() && identity.role().equals(role));
    }

    public void provision(UUID subject, String username, String passwordHash, String role) {
        if (jdbc != null) {
            jdbc.update("INSERT INTO auth_identities(subject, username, password_hash, role, enabled) VALUES (?, ?, ?, ?, TRUE)",
                    subject, username, passwordHash, role);
        } else {
            Identity previous = memory.putIfAbsent(username, new Identity(subject, username, passwordHash, role, true));
            if (previous != null) throw new IllegalArgumentException("Username already provisioned");
        }
    }

    public void disableSubject(String subject) {
        UUID id;
        try { id = UUID.fromString(subject); }
        catch (IllegalArgumentException ex) { return; } // The bootstrap admin has a username subject.
        if (jdbc != null) {
            jdbc.update("UPDATE auth_identities SET enabled=FALSE WHERE subject=?", id);
        } else {
            memory.replaceAll((username, identity) -> identity.subject().equals(id)
                    ? new Identity(id, username, identity.passwordHash(), identity.role(), false) : identity);
        }
    }

    public boolean updateSubject(UUID subject, String username, String role, boolean enabled) {
        if (jdbc != null) {
            return jdbc.update("UPDATE auth_identities SET username=?, role=?, enabled=? WHERE subject=?",
                    username, role, enabled, subject) == 1;
        }
        synchronized (memory) {
            Identity previous = memory.values().stream().filter(identity -> identity.subject().equals(subject))
                    .findFirst().orElse(null);
            if (previous == null) return false;
            Identity collision = memory.get(username);
            if (collision != null && !collision.subject().equals(subject))
                throw new IllegalArgumentException("Username already provisioned");
            memory.remove(previous.username());
            memory.put(username, new Identity(subject, username, previous.passwordHash(), role, enabled));
            return true;
        }
    }

    public record Identity(UUID subject, String username, String passwordHash, String role, boolean enabled) {}
}
