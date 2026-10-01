package com.travelplan.auth;

import com.travelplan.auth.security.IdentitySessionCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("infra")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth-infra-wiring;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "management.server.port=0"
})
class AuthInfraWiringTest {
    @Autowired PlatformTransactionManager transactions;
    @Autowired IdentitySessionCoordinator coordinator;
    @Autowired JdbcTemplate jdbc;

    @Test void infraProfileProvidesTransactionalJdbcIdentityWork() {
        assertThat(transactions).isNotNull();
        assertThat(coordinator.execute(() -> jdbc.queryForObject("SELECT 1", Integer.class))).isEqualTo(1);
    }
}
