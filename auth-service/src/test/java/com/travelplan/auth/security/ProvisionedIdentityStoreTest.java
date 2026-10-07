package com.travelplan.auth.security;

import com.travelplan.common.security.Role;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProvisionedIdentityStoreTest {
    @Test void provisioningAndDisablingAreSharedAcrossInstances() {
        JdbcTemplate jdbc = TokenServiceTest.migratedDatabase();
        UUID id = UUID.randomUUID();
        new ProvisionedIdentityStore(jdbc).provision(id, "person@example.com", "hash", Role.TRAVEL_MANAGER);

        var second = new ProvisionedIdentityStore(jdbc);
        assertThat(second.findByUsername("person@example.com").subject()).isEqualTo(id);
        assertThat(second.findBySubject(id)).get().extracting(ProvisionedIdentityStore.Identity::role).isEqualTo(Role.TRAVEL_MANAGER);
        assertThat(second.matchesCurrentRole(id, Role.TRAVEL_MANAGER)).isTrue();
        assertThat(second.matchesCurrentRole(id, Role.ADMIN)).isFalse();

        second.disableSubject(id);
        assertThat(new ProvisionedIdentityStore(jdbc).findByUsername("person@example.com").enabled()).isFalse();
        assertThat(second.matchesCurrentRole(id, Role.TRAVEL_MANAGER)).isFalse();
    }

    @Test void migrationMapsLegacyOperatorsToTravelersAndRejectsUnknownRoles() {
        var datasource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(datasource);
        jdbc.execute("CREATE DOMAIN TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE");
        Flyway.configure().dataSource(datasource).locations("classpath:db/migration").target("3").load().migrate();
        UUID operator = UUID.randomUUID();
        jdbc.update("INSERT INTO auth_identities(subject, username, password_hash, role, enabled) VALUES (?, 'op@example.com', 'hash', 'OPERATOR', TRUE)", operator);

        Flyway.configure().dataSource(datasource).locations("classpath:db/migration").load().migrate();

        assertThat(new ProvisionedIdentityStore(jdbc).findBySubject(operator)).get()
                .extracting(ProvisionedIdentityStore.Identity::role).isEqualTo(Role.TRAVELER);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO auth_identities(subject, username, password_hash, role, enabled)"
                + " VALUES (?, 'x@example.com', 'hash', 'OPERATOR', TRUE)", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
