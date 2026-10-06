package com.travelplan.engagement;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("infra")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:engagement-infra-wiring;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "management.server.port=0"
})
class EngagementInfraWiringTest {
    @Autowired JdbcTemplate jdbc;

    @Value("${local.management.port}")
    private int managementPort;

    @Test void infraProfileMigratesOutboxAndInboxTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT LOWER(table_name) FROM information_schema.tables WHERE LOWER(table_name) IN ('outbox_event', 'processed_event')",
                String.class);
        assertThat(tables).containsExactlyInAnyOrder("outbox_event", "processed_event");
    }

    @Test void healthIncludesDatabaseConnectivity() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/health")).GET().build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        }
    }
}
