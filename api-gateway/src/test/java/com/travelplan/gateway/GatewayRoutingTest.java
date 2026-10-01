package com.travelplan.gateway;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
class GatewayRoutingTest {

    private static final Map<String, HttpServer> UPSTREAMS = Map.of(
            "auth", upstream("auth"), "users", upstream("users"),
            "travel", upstream("travel"), "payments", upstream("payments"));
    private static final HttpServer VALIDATOR = validator();
    private static final AtomicReference<String> VALIDATION_REQUEST_ID = new AtomicReference<>();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void configureUpstreams(DynamicPropertyRegistry registry) {
        UPSTREAMS.forEach((name, server) -> registry.add("services." + name + ".url",
                () -> "http://127.0.0.1:" + server.getAddress().getPort()));
        registry.add("auth.service-url", () -> "http://127.0.0.1:" + VALIDATOR.getAddress().getPort());
    }

    @AfterAll
    static void stopUpstreams() {
        UPSTREAMS.values().forEach(server -> server.stop(0));
        VALIDATOR.stop(0);
    }

    @ParameterizedTest
    @CsvSource({
            "POST, /api/auth/login, auth",
            "GET, /api/users, users",
            "PUT, /api/users/123, users",
            "DELETE, /api/travelers/123, users",
            "GET, /api/travels, travel",
            "PUT, /api/travels/123/itinerary, travel",
            "GET, /api/destinations, travel",
            "POST, /api/payment-methods, payments",
            "DELETE, /api/payment-gateways/123, payments"
    })
    void forwardsToDomainOwnerPreservingRequest(String method, String path, String owner) throws Exception {
        String fullPath = path + "?page=1&size=20";
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + fullPath))
                .timeout(Duration.ofSeconds(10))
                .header("X-Request-Id", "routing-check")
                .method(method, HttpRequest.BodyPublishers.ofString("payload"));
        if (!path.equals("/api/auth/login")) builder.header("Authorization", "Bearer " + token());
        var request = builder.build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(owner + "|" + method + "|" + fullPath
                    + "|routing-check|payload");
        }
    }

    @Test
    void generatesAndForwardsRequestIdWhenCallerOmitsIt() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
                .POST(HttpRequest.BodyPublishers.ofString("payload")).build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String id = response.headers().firstValue("X-Request-ID").orElseThrow();
            assertThat(id).matches("[a-zA-Z0-9._-]{1,64}");
            assertThat(response.body()).isEqualTo("auth|POST|/api/auth/login|" + id + "|payload");
        }
    }

    @Test
    void replacesUnsafeRequestIdBeforeForwardingIt() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
                .header("X-Request-ID", "bad\"id")
                .POST(HttpRequest.BodyPublishers.ofString("payload")).build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String id = response.headers().firstValue("X-Request-ID").orElseThrow();
            assertThat(id).matches("[a-zA-Z0-9._-]{1,64}").isNotEqualTo("bad\"id");
            assertThat(response.body()).isEqualTo("auth|POST|/api/auth/login|" + id + "|payload");
        }
    }

    @Test
    void requestIdReachesTokenValidationAndUnauthorizedResponses() throws Exception {
        VALIDATION_REQUEST_ID.set(null);
        try (HttpClient client = HttpClient.newHttpClient()) {
            var valid = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/users"))
                    .header("Authorization", "Bearer " + token())
                    .header("X-Request-ID", "validation-trace-check").GET().build();
            var accepted = client.send(valid, HttpResponse.BodyHandlers.ofString());
            assertThat(accepted.statusCode()).isEqualTo(200);
            assertThat(VALIDATION_REQUEST_ID.get()).isEqualTo("validation-trace-check");

            var invalid = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/users"))
                    .header("Authorization", "Bearer invalid")
                    .header("X-Request-ID", "denial-trace-check").GET().build();
            var denied = client.send(invalid, HttpResponse.BodyHandlers.ofString());
            assertThat(denied.statusCode()).isEqualTo(401);
            assertThat(denied.headers().firstValue("X-Request-ID")).contains("denial-trace-check");
        }
    }

    @ParameterizedTest
    @CsvSource({"/api/unknown", "/api/users-extra", "/internal/cleanup", "/actuator/env"})
    void doesNotRouteUnownedOrPrivatePaths(String path) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET();
        if (path.startsWith("/api/")) builder.header("Authorization", "Bearer " + token());
        var request = builder.build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(404);
        }
    }

    private static HttpServer upstream(String owner) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    String body = owner + "|" + exchange.getRequestMethod() + "|" + exchange.getRequestURI()
                            + "|" + exchange.getRequestHeaders().getFirst("X-Request-Id") + "|"
                            + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start test upstream", exception);
        }
    }

    private static HttpServer validator() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/token-validation", exchange -> {
                try (exchange) {
                    VALIDATION_REQUEST_ID.set(exchange.getRequestHeaders().getFirst("X-Request-ID"));
                    int status = "change-me-internal".equals(exchange.getRequestHeaders().getFirst("X-Internal-Service-Token"))
                            && exchange.getRequestHeaders().getFirst("Authorization") != null ? 200 : 403;
                    exchange.sendResponseHeaders(status, -1);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    private static String token() throws Exception {
        String payload = "admin|ADMIN|travel-plan-auth|travel-plan-admin|"
                + (System.currentTimeMillis() / 1000 + 60) + "|" + UUID.randomUUID();
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("replace-with-at-least-32-random-characters".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return encoded + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(encoded.getBytes(StandardCharsets.UTF_8)));
    }
}
