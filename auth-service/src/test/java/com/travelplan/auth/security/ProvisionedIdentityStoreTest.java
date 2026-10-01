package com.travelplan.auth.security;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class ProvisionedIdentityStoreTest {
    @Test void provisioningAndDisablingAreSharedAcrossInstances() {
        var datasource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(datasource);
        jdbc.execute("CREATE DOMAIN TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE");
        Flyway.configure().dataSource(datasource).locations("classpath:db/migration").load().migrate();
        UUID id = UUID.randomUUID();
        new ProvisionedIdentityStore(jdbc).provision(id, "person@example.com", "hash", "OPERATOR");

        var second = new ProvisionedIdentityStore(jdbc);
        assertThat(second.findByUsername("person@example.com").subject()).isEqualTo(id);
        var firstTokens = new TokenService("01234567890123456789012345678901", 60,
                new TokenSessionStore(jdbc), new ProvisionedIdentityStore(jdbc));
        String token = firstTokens.issue(id.toString(), "OPERATOR").value();
        assertThat(new TokenService("01234567890123456789012345678901", 60,
                new TokenSessionStore(jdbc), second).verify(token)).isNotNull();
        second.disableSubject(id.toString());
        assertThat(new ProvisionedIdentityStore(jdbc).findByUsername("person@example.com").enabled()).isFalse();
        assertThat(firstTokens.verify(token)).isNull();
    }
}
