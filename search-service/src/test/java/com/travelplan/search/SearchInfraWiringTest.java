package com.travelplan.search;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class SearchInfraWiringTest {
    private static final HttpServer cluster = startCluster();

    private static HttpServer startCluster() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] body = "{\"cluster_name\":\"test\",\"status\":\"green\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.getResponseHeaders().add("X-Elastic-Product", "Elasticsearch");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @AfterAll static void stopCluster() {
        cluster.stop(0);
    }

    static int healthStatus(int managementPort) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/health")).GET().build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    @Nested
    @ActiveProfiles("infra")
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
    class ReachableCluster {
        @DynamicPropertySource static void elasticsearch(DynamicPropertyRegistry registry) {
            registry.add("spring.elasticsearch.uris", () -> "http://127.0.0.1:" + cluster.getAddress().getPort());
        }

        @Value("${local.management.port}") int managementPort;

        @Test void healthIsUpWhenClusterIsGreen() throws Exception {
            assertThat(healthStatus(managementPort)).isEqualTo(200);
        }
    }

    @Nested
    @ActiveProfiles("infra")
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "management.server.port=0",
            "spring.elasticsearch.uris=http://127.0.0.1:1",
            "spring.elasticsearch.connection-timeout=200ms"
    })
    class UnreachableCluster {
        @Value("${local.management.port}") int managementPort;

        @Test void healthIsDownWhenClusterIsUnreachable() throws Exception {
            assertThat(healthStatus(managementPort)).isEqualTo(503);
        }
    }
}
