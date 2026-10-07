package com.travelplan.auth.security;

import com.travelplan.common.security.Role;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdentitySessionCoordinatorTest {
    @Test void identityChangeRollsBackWhenSessionRevocationFails() {
        var datasource = TokenServiceTest.migratedDataSource();
        JdbcTemplate jdbc = new JdbcTemplate(datasource);
        var identities = new ProvisionedIdentityStore(jdbc);
        UUID id = UUID.randomUUID();
        identities.provision(id, "person@example.com", "hash", Role.ADMIN);
        var coordinator = new IdentitySessionCoordinator(new DataSourceTransactionManager(datasource));

        assertThrows(IllegalStateException.class, () -> coordinator.execute(() -> {
            identities.updateSubject(id, "person@example.com", Role.TRAVELER, false);
            throw new IllegalStateException("session update failed");
        }));
        assertThat(identities.findByUsername("person@example.com").role()).isEqualTo(Role.ADMIN);
        assertThat(identities.findByUsername("person@example.com").enabled()).isTrue();
    }
}
