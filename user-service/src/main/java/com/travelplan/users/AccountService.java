package com.travelplan.users;

import com.travelplan.common.security.Role;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Self-service account flows: sign-up and choosing a password from a setup link. */
@Service
public class AccountService {
    private final UserStore users;
    private final PasswordSetupStore setupTokens;
    private final AuthProvisionClient auth;
    private final TransactionTemplate transactions;

    public AccountService(UserStore users, PasswordSetupStore setupTokens, AuthProvisionClient auth,
                          ObjectProvider<PlatformTransactionManager> managers) {
        this.users = users;
        this.setupTokens = setupTokens;
        this.auth = auth;
        PlatformTransactionManager manager = managers.getIfAvailable();
        this.transactions = manager == null ? null : new TransactionTemplate(manager);
    }

    /** Public sign-up always creates a TRAVELER; only admins grant higher roles. */
    public UserDomain.User register(String email, String firstName, String lastName, String password) {
        return users.create(email, firstName, lastName, Role.TRAVELER, password);
    }

    public PasswordSetupStore.Issued issueSetupLink(UUID userId) {
        users.get(userId);
        return setupTokens.issue(userId);
    }

    /**
     * Sets the password for the token's user. The token stays valid if auth-service cannot
     * be reached, so the user can retry; it is consumed only after the password is set.
     */
    public void completeSetup(String token, String password) {
        inTransaction(() -> {
            UUID userId = setupTokens.findValidForUpdate(token).orElseThrow(InvalidSetupToken::new);
            if (users.get(userId).status() != UserDomain.Status.ACTIVE) throw new InvalidSetupToken();
            auth.setPassword(userId, password);
            setupTokens.markUsed(token);
            return null;
        });
    }

    private <T> T inTransaction(Supplier<T> work) {
        if (transactions != null) return transactions.execute(status -> work.get());
        synchronized (this) {
            return work.get();
        }
    }

    public static class InvalidSetupToken extends RuntimeException {}
}
