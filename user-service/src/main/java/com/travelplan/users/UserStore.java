package com.travelplan.users;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class UserStore {
    private static final Logger log = LoggerFactory.getLogger(UserStore.class);
    private final Map<UUID, UserDomain.User> users = new ConcurrentHashMap<>();
    private final Map<UUID, UserDomain.Traveler> travelers = new ConcurrentHashMap<>();
    private final Map<String, Object> idempotent = new ConcurrentHashMap<>();
    private final Map<UUID, String> deletionStages = new ConcurrentHashMap<>();
    private final RevocationClient revocations;
    private final AuthProvisionClient provisioning;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public UserStore(RevocationClient revocations, AuthProvisionClient provisioning, ObjectProvider<JdbcTemplate> provider) {
        this(revocations, provisioning, provider, null);
    }
    @Autowired
    public UserStore(RevocationClient revocations, AuthProvisionClient provisioning,
                     ObjectProvider<JdbcTemplate> provider, ObjectProvider<PlatformTransactionManager> managers) {
        this.revocations = revocations;
        this.provisioning = provisioning;
        this.jdbc = provider.getIfAvailable();
        PlatformTransactionManager manager = managers == null ? null : managers.getIfAvailable();
        this.transactions = manager == null ? null : new TransactionTemplate(manager);
        if (jdbc != null && transactions == null) throw new IllegalStateException("User transactions unavailable");
    }
    @PostConstruct void loadPersisted() {
        if (jdbc == null) return;
        jdbc.query("select id,email,first_name,last_name,role,status,created_at from users", r -> {
            var user = new UserDomain.User((UUID) r.getObject("id"), r.getString("email"),
                    r.getString("first_name"), r.getString("last_name"),
                    UserDomain.Role.valueOf(r.getString("role")), UserDomain.Status.valueOf(r.getString("status")),
                    r.getTimestamp("created_at").toInstant());
            users.put(user.id(), user);
        });
        jdbc.query("select id,user_id,passport_number,nationality,created_at from travelers", r -> {
            var traveler = new UserDomain.Traveler((UUID) r.getObject("id"), (UUID) r.getObject("user_id"),
                    r.getString("passport_number"), r.getString("nationality"),
                    r.getTimestamp("created_at").toInstant());
            travelers.put(traveler.id(), traveler);
        });
    }

    public UserDomain.User create(String email, String first, String last, UserDomain.Role role) {
        if (users.values().stream().anyMatch(u -> u.email().equalsIgnoreCase(email))) throw new Conflict("EMAIL_EXISTS");
        UserDomain.User user = new UserDomain.User(UUID.randomUUID(), email.toLowerCase(Locale.ROOT), first, last, role, UserDomain.Status.ACTIVE, Instant.now());
        try { provisioning.provision(user.id(), user.email(), UUID.randomUUID().toString()+"-Initial9!", user.role().name()); } catch (RuntimeException ex) { throw new Conflict("AUTH_PROVISIONING_FAILED"); }
        if (jdbc != null) {
            try {
                jdbc.update("insert into users(id,email,first_name,last_name,role,status,created_at) values (?,?,?,?,?,?,?)",
                        user.id(), user.email(), user.firstName(), user.lastName(), user.role().name(),
                        user.status().name(), Timestamp.from(user.createdAt()));
            } catch (RuntimeException insertFailure) {
                try {
                    revocations.revoke(user.id());
                } catch (RuntimeException revokeFailure) {
                    insertFailure.addSuppressed(revokeFailure);
                    log.error("Failed to disable provisioned auth identity {} after user insert failure", user.id(), revokeFailure);
                }
                throw insertFailure;
            }
        }
        users.put(user.id(), user);
        return user;
    }
    public List<UserDomain.User> list() {
        if (jdbc != null) return jdbc.query("select id,email,first_name,last_name,role,status,created_at "
                        + "from users order by created_at,id",
                (r, row) -> new UserDomain.User((UUID) r.getObject("id"), r.getString("email"),
                        r.getString("first_name"), r.getString("last_name"),
                        UserDomain.Role.valueOf(r.getString("role")), UserDomain.Status.valueOf(r.getString("status")),
                        r.getTimestamp("created_at").toInstant()));
        return users.values().stream().sorted(Comparator.comparing(UserDomain.User::createdAt).thenComparing(UserDomain.User::id)).toList();
    }
    public UserDomain.User get(UUID id) {
        if (jdbc != null) {
            List<UserDomain.User> found = jdbc.query("select id,email,first_name,last_name,role,status,created_at from users where id=?",
                    (r, row) -> new UserDomain.User((UUID) r.getObject("id"), r.getString("email"),
                            r.getString("first_name"), r.getString("last_name"),
                            UserDomain.Role.valueOf(r.getString("role")), UserDomain.Status.valueOf(r.getString("status")),
                            r.getTimestamp("created_at").toInstant()), id);
            if (found.isEmpty()) throw new NotFound();
            users.put(id, found.getFirst());
            return found.getFirst();
        }
        UserDomain.User u = users.get(id); if (u == null) throw new NotFound(); return u;
    }

    public boolean activeForPayment(UUID id) {
        if (jdbc != null) {
            List<Boolean> states = jdbc.query("select status,deletion_stage from users where id=?",
                    (r, row) -> "ACTIVE".equals(r.getString("status"))
                            && r.getString("deletion_stage") == null, id);
            if (states.isEmpty()) throw new NotFound();
            return states.getFirst();
        }
        return get(id).status() == UserDomain.Status.ACTIVE && !deletionStages.containsKey(id);
    }

    public String deletionStage(UUID id) {
        if (jdbc != null) {
            List<String> found = jdbc.query("select deletion_stage from users where id=?",
                    (r, row) -> r.getString(1), id);
            if (found.isEmpty()) throw new NotFound();
            return found.getFirst();
        }
        get(id);
        return deletionStages.get(id);
    }

    public synchronized void beginDeletionIfAllowed(UUID id) {
        if (jdbc != null) {
            transactions.executeWithoutResult(tx -> {
                lockLifecycle();
                UserDomain.User user = get(id);
                if (deletionStage(id) == null) {
                    if (user.role() == UserDomain.Role.ADMIN && user.status() == UserDomain.Status.ACTIVE
                            && activeAdminCount() <= 1) throw new Conflict("LAST_ADMIN");
                    jdbc.update("update users set deletion_stage='STARTED' where id=? and deletion_stage is null", id);
                }
            });
        } else if (!deletionStages.containsKey(id)) {
            UserDomain.User user = get(id);
            if (user.role() == UserDomain.Role.ADMIN && user.status() == UserDomain.Status.ACTIVE
                    && activeAdminCount() <= 1) throw new Conflict("LAST_ADMIN");
            deletionStages.put(id, "STARTED");
        }
    }

    private void lockLifecycle() {
        jdbc.query("select id from user_lifecycle_lock where id=1 for update", (java.sql.ResultSet r) -> {});
    }

    public void advanceDeletion(UUID id, String expected, String next) {
        if (jdbc != null) jdbc.update("update users set deletion_stage=? where id=? and deletion_stage=?", next, id, expected);
        else deletionStages.replace(id, expected, next);
    }

    public void disableForDeletion(UUID id) {
        UserDomain.User old = get(id);
        if (jdbc != null) jdbc.update("update users set status='DISABLED' where id=? and deletion_stage is not null", id);
        users.put(id, new UserDomain.User(old.id(), old.email(), old.firstName(), old.lastName(),
                old.role(), UserDomain.Status.DISABLED, old.createdAt()));
    }

    public List<UUID> travelerIds(UUID userId) {
        if (jdbc != null) return jdbc.query("select id from travelers where user_id=?", (r, row) -> (UUID) r.getObject(1), userId);
        return travelers.values().stream().filter(t -> userId.equals(t.userId())).map(UserDomain.Traveler::id).toList();
    }

    public void removeTravelersForUser(UUID userId) {
        if (jdbc != null) jdbc.update("delete from travelers where user_id=?", userId);
        travelers.values().removeIf(t -> userId.equals(t.userId()));
    }

    public long activeAdminCount() {
        if (jdbc != null) return jdbc.queryForObject(
                "select count(*) from users where role='ADMIN' and status='ACTIVE' and deletion_stage is null",
                Long.class);
        return users.values().stream().filter(u -> u.role() == UserDomain.Role.ADMIN
                && u.status() == UserDomain.Status.ACTIVE && !deletionStages.containsKey(u.id())).count();
    }
    public synchronized UserDomain.User update(UUID id, String email, String first, String last, UserDomain.Role role, UserDomain.Status status) {
        if (jdbc != null) return transactions.execute(tx -> {
            lockLifecycle();
            List<UUID> locked = jdbc.query("select id from users where id=? for update",
                    (r, row) -> (UUID) r.getObject(1), id);
            if (locked.isEmpty()) throw new NotFound();
            return updateLocked(id, email, first, last, role, status);
        });
        return updateLocked(id, email, first, last, role, status);
    }

    private UserDomain.User updateLocked(UUID id, String email, String first, String last,
                                          UserDomain.Role role, UserDomain.Status status) {
        UserDomain.User old = get(id);
        if (deletionStage(id) != null) throw new Conflict("USER_DELETION_IN_PROGRESS");
        if (email != null && users.values().stream().anyMatch(u -> !u.id().equals(id) && u.email().equalsIgnoreCase(email))) throw new Conflict("EMAIL_EXISTS");
        UserDomain.User next = new UserDomain.User(id, email == null ? old.email() : email.toLowerCase(Locale.ROOT), first == null ? old.firstName() : first, last == null ? old.lastName() : last, role == null ? old.role() : role, status == null ? old.status() : status, old.createdAt());
        if (old.role() == UserDomain.Role.ADMIN && old.status() == UserDomain.Status.ACTIVE
                && (next.role() != UserDomain.Role.ADMIN || next.status() != UserDomain.Status.ACTIVE)
                && activeAdminCount() <= 1)
            throw new Conflict("LAST_ADMIN");
        if (!old.email().equals(next.email()) || old.role() != next.role() || old.status() != next.status()) {
            try { provisioning.update(id, next.email(), next.role().name(), next.status() == UserDomain.Status.ACTIVE); }
            catch (RuntimeException ex) { throw new Conflict("AUTH_UPDATE_FAILED"); }
        }
        if (jdbc != null && jdbc.update("update users set email=?,first_name=?,last_name=?,role=?,status=? "
                + "where id=? and deletion_stage is null", next.email(), next.firstName(), next.lastName(),
                next.role().name(), next.status().name(), id) == 0) throw new Conflict("USER_DELETION_IN_PROGRESS");
        users.put(id, next); return next;
    }
    public synchronized UserDomain.Traveler createTraveler(UUID userId, String passport, String nationality) { if (!activeForPayment(userId)) throw new Conflict("USER_NOT_ACTIVE"); UserDomain.Traveler t = new UserDomain.Traveler(UUID.randomUUID(), userId, passport, nationality, Instant.now()); if (jdbc != null && jdbc.update("insert into travelers(id,user_id,passport_number,nationality,created_at) "
            + "select ?,id,?,?,? from users where id=? and status='ACTIVE' and deletion_stage is null for update",
            t.id(), t.passportNumber(), t.nationality(), Timestamp.from(t.createdAt()), userId) == 0)
            throw new Conflict("USER_NOT_ACTIVE"); travelers.put(t.id(), t); return t; }
    public List<UserDomain.Traveler> travelers(UUID userId) {
        if (jdbc != null) {
            String sql = "select id,user_id,passport_number,nationality,created_at from travelers"
                    + (userId == null ? "" : " where user_id=?") + " order by created_at,id";
            return userId == null ? jdbc.query(sql, (r, row) -> traveler(r))
                    : jdbc.query(sql, (r, row) -> traveler(r), userId);
        }
        return travelers.values().stream().filter(t -> userId == null || t.userId().equals(userId))
                .sorted(Comparator.comparing(UserDomain.Traveler::createdAt)).toList();
    }

    public UserDomain.Traveler traveler(UUID id) {
        if (jdbc != null) {
            List<UserDomain.Traveler> found = jdbc.query("select id,user_id,passport_number,nationality,created_at "
                    + "from travelers where id=?", (r, row) -> traveler(r), id);
            if (found.isEmpty()) throw new NotFound();
            return found.getFirst();
        }
        UserDomain.Traveler t = travelers.get(id); if (t == null) throw new NotFound(); return t;
    }

    private static UserDomain.Traveler traveler(java.sql.ResultSet r) throws java.sql.SQLException {
        return new UserDomain.Traveler((UUID) r.getObject("id"), (UUID) r.getObject("user_id"),
                r.getString("passport_number"), r.getString("nationality"), r.getTimestamp("created_at").toInstant());
    }

    public void deleteTraveler(UUID id) {
        traveler(id);
        if (jdbc != null && jdbc.update("delete from travelers where id=?", id) == 0) throw new NotFound();
        travelers.remove(id);
    }

    public UserDomain.Traveler updateTraveler(UUID id, String passport, String nationality) {
        var old = traveler(id);
        if (deletionStage(old.userId()) != null) throw new Conflict("USER_DELETION_IN_PROGRESS");
        var next = new UserDomain.Traveler(id, old.userId(), passport == null ? old.passportNumber() : passport,
                nationality == null ? old.nationality() : nationality, old.createdAt());
        if (jdbc != null && jdbc.update("update travelers set passport_number=?,nationality=? where id=?",
                next.passportNumber(), next.nationality(), id) == 0) throw new NotFound();
        travelers.put(id, next);
        return next;
    }
    public Object idempotent(String key) { return key == null || key.isBlank() ? null : idempotent.get(key); }
    public void remember(String key, Object value) { if (key != null && !key.isBlank()) idempotent.putIfAbsent(key, value); }
    public static class NotFound extends RuntimeException {}
    public static class Conflict extends RuntimeException { public final String code; public Conflict(String code) { this.code = code; } }
}
