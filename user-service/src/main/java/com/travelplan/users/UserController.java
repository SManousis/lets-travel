package com.travelplan.users;

import com.travelplan.common.audit.AuditLog;
import com.travelplan.common.security.AccessToken;
import com.travelplan.common.security.Ownership;
import com.travelplan.common.security.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Accounts (admins only, enforced in SecurityConfig) and traveler profiles (each user their own, admins all).
 * A profile the caller may not see is reported as missing (404), never as forbidden.
 */
@RestController
public class UserController {
    private static final Logger log = LoggerFactory.getLogger(UserController.class);
    private final UserStore store;
    private final UserDeletionService deletion;
    private final UserDeletionClients cleanup;
    private final AccountService accounts;
    private final AuditLog audit;

    public UserController(UserStore store, UserDeletionService deletion, UserDeletionClients cleanup, AccountService accounts,
                          AuditLog audit) {
        this.store = store;
        this.deletion = deletion;
        this.cleanup = cleanup;
        this.accounts = accounts;
        this.audit = audit;
    }

    // ---- Accounts ------------------------------------------------------------------------------

    @GetMapping("/api/users")
    public Page<UserDomain.User> users(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return page(store.list(), page, size);
    }

    /** The response carries a one-time password-setup link to hand to the new user. */
    @PostMapping("/api/users")
    public ResponseEntity<?> create(@Valid @RequestBody UserCreate body,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        Object earlier = store.idempotent(key);
        if (earlier != null) return ResponseEntity.ok(earlier);
        UserDomain.User user = store.create(body.email(), body.firstName(), body.lastName(), body.role());
        PasswordSetupStore.Issued link = accounts.issueSetupLink(user.id());
        CreatedUser created = new CreatedUser(user.id(), user.email(), user.firstName(), user.lastName(), user.role(),
                user.status(), user.createdAt(), new AccountController.SetupLink(link.token(), link.expiresAt()));
        store.remember(key, created);
        audit.record("USER_CREATED", "USER", user.id(), Map.of("role", user.role()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/api/users/{id}")
    public UserDomain.User user(@PathVariable UUID id) {
        return store.get(id);
    }

    @PatchMapping("/api/users/{id}")
    public UserDomain.User update(@PathVariable UUID id, @Valid @RequestBody UserPatch body) {
        UserDomain.User before = store.get(id);
        UserDomain.User after = store.update(id, body.email(), body.firstName(), body.lastName(), body.role(), body.status());
        Map<String, Object> changes = new LinkedHashMap<>();
        if (before.role() != after.role()) changes.put("role", before.role() + " -> " + after.role());
        if (!Objects.equals(before.status(), after.status())) changes.put("status", before.status() + " -> " + after.status());
        if (!Objects.equals(before.email(), after.email())) changes.put("emailChanged", true);
        if (!changes.isEmpty()) audit.record("USER_UPDATED", "USER", id, changes);
        return after;
    }

    /** 204 when the account is gone; 202 while a clean-up step waits for another service (call again). */
    @DeleteMapping("/api/users/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        boolean finished = deletion.delete(id);
        audit.record("USER_DELETED", "USER", id, Map.of("completed", finished));
        return finished ? ResponseEntity.noContent().build() : ResponseEntity.accepted().build();
    }

    // ---- Traveler profiles ---------------------------------------------------------------------

    @GetMapping("/api/travelers")
    public Page<UserDomain.Traveler> travelers(@AuthenticationPrincipal AccessToken caller,
                                               @RequestParam(required = false) UUID userId,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return page(store.travelers(Ownership.listScope(caller, userId)), page, size);
    }

    @PostMapping("/api/travelers")
    public ResponseEntity<UserDomain.Traveler> createTraveler(@AuthenticationPrincipal AccessToken caller,
                                                              @Valid @RequestBody TravelerCreate body) {
        if (!Ownership.canAccess(caller, body.userId())) throw new UserStore.NotFound();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(store.createTraveler(body.userId(), body.passportNumber(), body.nationality()));
    }

    @GetMapping("/api/travelers/{id}")
    public UserDomain.Traveler traveler(@AuthenticationPrincipal AccessToken caller, @PathVariable UUID id) {
        return owned(caller, id);
    }

    @PatchMapping("/api/travelers/{id}")
    public UserDomain.Traveler updateTraveler(@AuthenticationPrincipal AccessToken caller, @PathVariable UUID id,
                                              @Valid @RequestBody TravelerPatch body) {
        owned(caller, id);
        return store.updateTraveler(id, body.passportNumber(), body.nationality());
    }

    /** The profile's seat reservations and travel history are removed first (travel-service). */
    @DeleteMapping("/api/travelers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTraveler(@AuthenticationPrincipal AccessToken caller, @PathVariable UUID id) {
        owned(caller, id);
        cleanup.cleanupTravelers(List.of(id));
        store.deleteTraveler(id);
    }

    private UserDomain.Traveler owned(AccessToken caller, UUID id) {
        UserDomain.Traveler traveler = store.traveler(id);
        if (!Ownership.canAccess(caller, traveler.userId())) throw new UserStore.NotFound();
        return traveler;
    }

    // ---- Errors --------------------------------------------------------------------------------

    @ExceptionHandler(UserStore.NotFound.class)
    ResponseEntity<Error> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Error("NOT_FOUND", "Resource not found"));
    }

    @ExceptionHandler(UserStore.Conflict.class)
    ResponseEntity<Error> conflict(UserStore.Conflict conflict) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Error(conflict.code, "Request conflicts with current state"));
    }

    @ExceptionHandler(UserDeletionClients.Unavailable.class)
    ResponseEntity<Error> cleanupUnavailable(UserDeletionClients.Unavailable unavailable) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new Error(unavailable.code, "Cleanup dependency unavailable"));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Error> storeUnavailable(DataAccessException failure) {
        log.error("User storage operation failed", failure);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new Error("USER_STORE_UNAVAILABLE", "User storage unavailable"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Error> invalid() {
        return ResponseEntity.badRequest().body(new Error("INVALID_REQUEST", "Request validation failed"));
    }

    private static <T> Page<T> page(List<T> all, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException();
        int from = Math.min(page * size, all.size());
        return new Page<>(all.subList(from, Math.min(from + size, all.size())), page, size, all.size());
    }

    public record Page<T>(List<T> items, int page, int size, long total) {}

    public record Error(String code, String message) {}

    public record CreatedUser(UUID id, String email, String firstName, String lastName, Role role, UserDomain.Status status,
                              Instant createdAt, AccountController.SetupLink passwordSetup) {}

    /** A new account is a traveler unless the admin chooses another role. */
    public record UserCreate(@NotBlank @Email String email, @NotBlank String firstName, @NotBlank String lastName, Role role) {
        public UserCreate {
            if (role == null) role = Role.TRAVELER;
        }
    }

    public record UserPatch(@Email String email, String firstName, String lastName, Role role, UserDomain.Status status) {}

    public record TravelerCreate(@NotNull UUID userId, @NotBlank String passportNumber, @NotBlank String nationality) {}

    public record TravelerPatch(@NotBlank String passportNumber, @NotBlank String nationality) {}
}
