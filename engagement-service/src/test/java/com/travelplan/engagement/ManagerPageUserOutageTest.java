package com.travelplan.engagement;

import com.sun.net.httpserver.HttpServer;
import com.travelplan.common.security.AccessTokenCodec;
import com.travelplan.common.security.Role;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** User-service-only outage: users stub is stopped, travel stub stays up. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "security.internal-service-token=test-internal"})
class ManagerPageUserOutageTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID MANAGER = UUID.randomUUID();
    private static final HttpServer AUTH = stub("/internal/token-validation", null);
    private static final HttpServer USERS = stub("/internal/managers", "{\"managerId\":\"" + MANAGER
            + "\",\"displayName\":\"Maria Manager\",\"memberSince\":\"2025-03-04T12:30:00Z\"}");
    private static final HttpServer TRAVEL = stub("/internal/managers", "{\"managerId\":\"" + MANAGER
            + "\",\"completedTravelCount\":1,\"totalTravelCount\":2}");
    @Autowired AccessTokenCodec codec;
    @Value("${local.server.port}") int port;

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        registry.add("auth.service-url", () -> "http://127.0.0.1:" + AUTH.getAddress().getPort());
        registry.add("services.users.url", () -> "http://127.0.0.1:" + USERS.getAddress().getPort());
        registry.add("services.travel.url", () -> "http://127.0.0.1:" + TRAVEL.getAddress().getPort());
    }

    @AfterAll static void stop() { AUTH.stop(0); USERS.stop(0); TRAVEL.stop(0); }

    @Test void unreachableUserServiceAloneGivesSafe503() throws Exception {
        assertThat(get().statusCode()).isEqualTo(200);

        USERS.stop(0);
        assertUnavailable(get());
    }

    private static void assertUnavailable(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(JSON.readTree(response.body()).get("code").asString()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(response.body()).doesNotContain("Maria Manager", "completedTravels", "127.0.0.1");
    }

    private HttpResponse<String> get() throws Exception {
        String token = codec.issue(UUID.randomUUID(), Role.TRAVELER, UUID.randomUUID()).value();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/managers/" + MANAGER))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token).GET().build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static HttpServer stub(String path, String body) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(path, exchange -> {
                try (exchange) {
                    if (body == null) { exchange.sendResponseHeaders(200, -1); return; }
                    String text = body;
                    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
            return server;
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
