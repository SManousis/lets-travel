package com.travelplan.users;

import com.travelplan.common.security.AccessToken;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

/** Sign-up, password setup and the signed-in user's own profile. */
@RestController
public class AccountController {
    private final AccountService accounts;
    private final UserStore users;

    public AccountController(AccountService accounts, UserStore users) {
        this.accounts = accounts;
        this.users = users;
    }

    @PostMapping("/api/registrations")
    public ResponseEntity<Me> register(@Valid @RequestBody Registration body) {
        requireFits(body.password());
        UserDomain.User user = accounts.register(body.email(), body.firstName(), body.lastName(), body.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(Me.of(user));
    }

    @PostMapping("/api/password-setup")
    public ResponseEntity<Void> completeSetup(@Valid @RequestBody PasswordSetup body) {
        requireFits(body.password());
        accounts.completeSetup(body.token(), body.password());
        return ResponseEntity.noContent().build();
    }

    /** Admin only (enforced in SecurityConfig): a new one-time link replaces earlier ones. */
    @PostMapping("/api/users/{id}/password-setup")
    public SetupLink issueSetupLink(@PathVariable UUID id) {
        PasswordSetupStore.Issued issued = accounts.issueSetupLink(id);
        return new SetupLink(issued.token(), issued.expiresAt());
    }

    /**
     * The caller's profile, read from the verified token's user ID. The bootstrap admin has
     * no profile, so only its ID and role are returned.
     */
    @GetMapping("/api/me")
    public Me me(@AuthenticationPrincipal AccessToken token) {
        try {
            return Me.of(users.get(token.userId()));
        } catch (UserStore.NotFound missing) {
            return new Me(token.userId(), null, null, null, token.role().name(), UserDomain.Status.ACTIVE.name(), null);
        }
    }

    @ExceptionHandler(AccountService.InvalidSetupToken.class)
    ResponseEntity<UserController.Error> invalidToken() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new UserController.Error("INVALID_SETUP_TOKEN", "The link is invalid, expired or already used"));
    }

    @ExceptionHandler(UserStore.Conflict.class)
    ResponseEntity<UserController.Error> conflict(UserStore.Conflict conflict) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new UserController.Error(conflict.code, "Request conflicts with current state"));
    }

    @ExceptionHandler(UserStore.NotFound.class)
    ResponseEntity<UserController.Error> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new UserController.Error("NOT_FOUND", "Resource not found"));
    }

    /** BCrypt reads at most 72 bytes; multi-byte characters count more than once. */
    private static void requireFits(String password) {
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) throw new PasswordTooLong();
    }

    static class PasswordTooLong extends RuntimeException {}

    @ExceptionHandler({MethodArgumentNotValidException.class, PasswordTooLong.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<UserController.Error> invalid() {
        return ResponseEntity.badRequest().body(new UserController.Error("VALIDATION_ERROR", "Request validation failed"));
    }

    @ExceptionHandler(RestClientException.class)
    ResponseEntity<UserController.Error> authUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new UserController.Error("DEPENDENCY_UNAVAILABLE", "Authentication service unavailable"));
    }

    public record Registration(@NotBlank @Email @Size(max = 254) String email,
                               @NotBlank @Size(max = 120) String firstName,
                               @NotBlank @Size(max = 120) String lastName,
                               @NotBlank @Size(min = 10, max = 72) String password) {}

    public record PasswordSetup(@NotBlank @Size(max = 128) String token,
                                @NotBlank @Size(min = 10, max = 72) String password) {}

    public record SetupLink(String token, Instant expiresAt) {}

    public record Me(UUID userId, String email, String firstName, String lastName, String role, String status, Instant memberSince) {
        static Me of(UserDomain.User user) {
            return new Me(user.id(), user.email(), user.firstName(), user.lastName(), user.role().name(),
                    user.status().name(), user.createdAt());
        }
    }
}
