package com.travelplan.travel;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "security.internal-service-token=test-internal"})
class TravelAuthorizationTest {
    private static final AtomicInteger authStatus = new AtomicInteger(200);
    private static final HttpServer AUTH = authServer();
    @Value("${local.server.port}") int port;

    @DynamicPropertySource
    static void authUrl(DynamicPropertyRegistry registry) {
        registry.add("auth.service-url", () -> "http://127.0.0.1:" + AUTH.getAddress().getPort());
    }

    @AfterAll static void stopAuth() { AUTH.stop(0); }

    @Test void activeTokenReachesTravelApi() throws Exception {
        authStatus.set(200);
        assertThat(getTravels().statusCode()).isEqualTo(200);
    }

    @Test void revokedTokenIsDeniedAtDirectService() throws Exception {
        authStatus.set(401);
        assertThat(getTravels().statusCode()).isEqualTo(401);
    }

    @Test void authOutageFailsClosed() throws Exception {
        authStatus.set(500);
        assertThat(getTravels().statusCode()).isEqualTo(503);
    }

    private HttpResponse<String> getTravels() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/travels"))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + token()).GET().build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static HttpServer authServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/token-validation", exchange -> {
                try (exchange) {
                    int status = "test-internal".equals(exchange.getRequestHeaders().getFirst("X-Internal-Service-Token"))
                            && exchange.getRequestHeaders().getFirst("Authorization") != null ? authStatus.get() : 403;
                    exchange.sendResponseHeaders(status, -1);
                }
            });
            server.start();
            return server;
        } catch (Exception ex) { throw new IllegalStateException(ex); }
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
