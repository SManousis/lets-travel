package com.travelplan.auth.security;

import com.travelplan.common.security.Role;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** Login identities. Every identity, including the bootstrap admin, has a UUID subject. */
@Component
public class ProvisionedIdentityStore {
    private static final RowMapper<Identity> ROWS = (rs, row) -> new Identity(rs.getObject("subject", UUID.class),
            rs.getString("username"), rs.getString("password_hash"), Role.valueOf(rs.getString("role")), rs.getBoolean("enabled"));

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
                + (lock ? " FOR UPDATE" : ""), ROWS, username).stream().findFirst().orElse(null);
    }

    public Optional<Identity> findBySubject(UUID subject) {
        if (jdbc == null) return memory.values().stream().filter(identity -> identity.subject().equals(subject)).findFirst();
        return jdbc.query("SELECT subject, username, password_hash, role, enabled FROM auth_identities WHERE subject=?",
                ROWS, subject).stream().findFirst();
    }

    /** True while the identity is enabled and still holds exactly this role. */
    public boolean matchesCurrentRole(UUID subject, Role role) {
        return findBySubject(subject).filter(identity -> identity.enabled() && identity.role() == role).isPresent();
    }

    public void provision(UUID subject, String username, String passwordHash, Role role) {
        if (jdbc != null) {
            jdbc.update("INSERT INTO auth_identities(subject, username, password_hash, role, enabled) VALUES (?, ?, ?, ?, TRUE)",
                    subject, username, passwordHash, role.name());
        } else {
            Identity previous = memory.putIfAbsent(username, new Identity(subject, username, passwordHash, role, true));
            if (previous != null) throw new IllegalArgumentException("Username already provisioned");
        }
    }

    public void disableSubject(UUID subject) {
        if (jdbc != null) {
            jdbc.update("UPDATE auth_identities SET enabled=FALSE WHERE subject=?", subject);
        } else {
            memory.replaceAll((username, identity) -> identity.subject().equals(subject)
                    ? new Identity(subject, username, identity.passwordHash(), identity.role(), false) : identity);
        }
    }

    public boolean updateSubject(UUID subject, String username, Role role, boolean enabled) {
        if (jdbc != null) {
            return jdbc.update("UPDATE auth_identities SET username=?, role=?, enabled=? WHERE subject=?",
                    username, role.name(), enabled, subject) == 1;
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

    public record Identity(UUID subject, String username, String passwordHash, Role role, boolean enabled) {}
}
