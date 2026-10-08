package com.travelplan.gateway;

import com.sun.net.httpserver.HttpServer;
import com.travelplan.common.security.AccessTokenCodec;
import com.travelplan.common.security.Role;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Body size and per-caller rate limits, with the limits lowered so the test stays small. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "gateway.limits.max-body-bytes=100",
        "gateway.limits.search-per-minute=2",
        "gateway.limits.registrations-per-hour=1"
})
class RequestLimitsTest {
    private static final HttpServer UPSTREAM = upstream();
    private static final AccessTokenCodec CODEC = new AccessTokenCodec("replace-with-at-least-32-random-characters", Duration.ofMinutes(1));

    @Value("${local.server.port}") int port;

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry registry) {
        String url = "http://127.0.0.1:" + UPSTREAM.getAddress().getPort();
        for (String name : new String[] {"auth", "users", "travel", "payments", "engagement", "search"}) {
            registry.add("services." + name + ".url", () -> url);
        }
        registry.add("auth.service-url", () -> url);
    }

    @AfterAll static void stop() { UPSTREAM.stop(0); }

    @Test void searchesAreLimitedPerUserNotForEveryone() throws Exception {
        String ana = token();
        assertThat(get("/api/search/travels?q=a", ana).statusCode()).isEqualTo(200);
        assertThat(get("/api/search/travels?q=b", ana).statusCode()).isEqualTo(200);
        HttpResponse<String> limited = get("/api/search/travels?q=c", ana);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.body()).contains("RATE_LIMITED");
        assertThat(limited.headers().firstValue("Retry-After")).hasValueSatisfying(seconds ->
                assertThat(Integer.parseInt(seconds)).isBetween(1, 60));

        assertThat(get("/api/search/travels?q=a", token()).statusCode()).as("another user has their own allowance").isEqualTo(200);
        assertThat(get("/api/travels", ana).statusCode()).as("other endpoints are not limited").isEqualTo(200);
    }

    @Test void signUpsAreLimitedPerAddress() throws Exception {
        String address = "203.0.113." + (int) (Math.random() * 200);
        assertThat(post("/api/registrations", "{}", address).statusCode()).isEqualTo(200);
        assertThat(post("/api/registrations", "{}", address).statusCode()).isEqualTo(429);
        assertThat(post("/api/registrations", "{}", "198.51.100.7").statusCode()).isEqualTo(200);
    }

    @Test void oversizedBodiesAreRejectedBeforeReachingAService() throws Exception {
        HttpResponse<String> response = post("/api/auth/login", "x".repeat(101), "192.0.2.1");
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("PAYLOAD_TOO_LARGE");
        assertThat(post("/api/auth/login", "x".repeat(100), "192.0.2.1").statusCode()).isEqualTo(200);
    }

    private static String token() {
        return CODEC.issue(UUID.randomUUID(), Role.TRAVELER, UUID.randomUUID()).value();
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + token).GET().build());
    }

    private HttpResponse<String> post(String path, String body, String address) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("X-Real-IP", address)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    /** Answers 200 to everything, including the gateway's session check. */
    private static HttpServer upstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
