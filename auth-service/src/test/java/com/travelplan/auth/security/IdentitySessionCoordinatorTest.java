package com.travelplan.auth.security;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdentitySessionCoordinatorTest {
    @Test void identityChangeRollsBackWhenSessionRevocationFails() {
        var datasource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(datasource);
        jdbc.execute("CREATE DOMAIN TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE");
        Flyway.configure().dataSource(datasource).locations("classpath:db/migration").load().migrate();
        var identities = new ProvisionedIdentityStore(jdbc);
        UUID id = UUID.randomUUID();
        identities.provision(id, "person@example.com", "hash", "ADMIN");
        var coordinator = new IdentitySessionCoordinator(new DataSourceTransactionManager(datasource));

        assertThrows(IllegalStateException.class, () -> coordinator.execute(() -> {
            identities.updateSubject(id, "person@example.com", "OPERATOR", false);
            throw new IllegalStateException("session update failed");
        }));
        assertThat(identities.findByUsername("person@example.com").role()).isEqualTo("ADMIN");
        assertThat(identities.findByUsername("person@example.com").enabled()).isTrue();
    }
}
