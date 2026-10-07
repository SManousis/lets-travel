package com.travelplan.auth.security;

import com.travelplan.common.security.Role;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first admin identity from ADMIN_USERNAME/ADMIN_PASSWORD when it does not
 * exist yet. Later changes to that identity are made through the user APIs, not config.
 */
@Component
public class BootstrapAdmin implements ApplicationRunner {
    private final ProvisionedIdentityStore identities;
    private final IdentitySessionCoordinator coordinator;
    private final PasswordEncoder passwords;
    private final String username;
    private final String password;

    public BootstrapAdmin(ProvisionedIdentityStore identities, IdentitySessionCoordinator coordinator, PasswordEncoder passwords,
                          @Value("${security.admin-username:admin}") String username,
                          @Value("${security.admin-password:change-me-admin}") String password) {
        this.identities = identities;
        this.coordinator = coordinator;
        this.passwords = passwords;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        coordinator.execute(() -> {
            if (identities.findByUsernameForUpdate(username) == null) {
                identities.provision(UUID.randomUUID(), username, passwords.encode(password), Role.ADMIN);
            }
            return null;
        });
    }
}
