package com.travelplan.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
class StartupTest {

    @Value("${local.server.port}")
    private int applicationPort;

    @Value("${local.management.port}")
    private int managementPort;

    @Test
    void reportsHealthOnSeparateManagementListener() throws Exception {
        assertThat(managementPort).isNotEqualTo(applicationPort);
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (String path : new String[] {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}) {
                var response = get(client, managementPort, path);
                assertThat(response.statusCode()).as(path).isEqualTo(200);
                var health = JsonMapper.builder().build().readTree(response.body());
                assertThat(health.path("status").asString()).isEqualTo("UP");
                assertThat(health.has("components")).isFalse();
            }
        }
    }

    @Test
    void keepsManagementAndInternalApisOffApplicationListener() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertThat(get(client, applicationPort, "/actuator/health").statusCode()).isEqualTo(404);
            assertThat(get(client, applicationPort, "/internal/cleanup").statusCode()).isEqualTo(404);
            assertThat(get(client, managementPort, "/actuator/env").statusCode()).isEqualTo(404);
        }
    }

    private HttpResponse<String> get(HttpClient client, int port, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
