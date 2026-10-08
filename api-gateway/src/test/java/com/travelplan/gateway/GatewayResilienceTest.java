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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** A hung service is cut off by the response timeout; a dropped connection is retried for reads only. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        // Shortened from 30s so the test stays quick; the behaviour is the same.
        "spring.cloud.gateway.server.webflux.httpclient.response-timeout=1s"
})
class GatewayResilienceTest {
    private static final AtomicInteger calls = new AtomicInteger();
    /** How many of the next calls the flaky upstream drops without answering. */
    private static final AtomicInteger dropNext = new AtomicInteger();
    private static final CountDownLatch release = new CountDownLatch(1);
    private static final HttpServer FLAKY = flaky();
    private static final HttpServer HUNG = hung();
    private static final HttpServer VALIDATOR = validator();

    @Value("${local.server.port}") int port;

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry registry) {
        String flaky = "http://127.0.0.1:" + FLAKY.getAddress().getPort();
        for (String name : new String[] {"auth", "users", "payments", "engagement", "search"}) {
            registry.add("services." + name + ".url", () -> flaky);
        }
        registry.add("services.travel.url", () -> "http://127.0.0.1:" + HUNG.getAddress().getPort());
        registry.add("auth.service-url", () -> "http://127.0.0.1:" + VALIDATOR.getAddress().getPort());
    }

    @AfterAll static void stop() {
        release.countDown();
        FLAKY.stop(0);
        HUNG.stop(0);
        VALIDATOR.stop(0);
    }

    @BeforeEach void reset() {
        calls.set(0);
        dropNext.set(0);
    }

    @Test void aHungServiceIsAnsweredWithGatewayTimeoutInsteadOfWaiting() throws Exception {
        long started = System.nanoTime();
        HttpResponse<String> response = send("GET", "/api/travels");
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
        assertThat(response.statusCode()).isEqualTo(504);
        assertThat(seconds).isLessThan(5);
    }

    @Test void aDroppedConnectionIsRetriedForReads() throws Exception {
        dropNext.set(1);
        HttpResponse<String> response = send("GET", "/api/subscriptions");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(calls).hasValue(2);
    }

    @Test void writesAreNeverRetried() throws Exception {
        dropNext.set(1);
        HttpResponse<String> response = send("POST", "/api/subscriptions");
        assertThat(response.statusCode()).isGreaterThanOrEqualTo(500);
        assertThat(calls).as("a write might have been applied, so it is not repeated").hasValue(1);
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + new AccessTokenCodec("replace-with-at-least-32-random-characters",
                        Duration.ofMinutes(1)).issue(UUID.randomUUID(), Role.ADMIN, UUID.randomUUID()).value())
                .method(method, HttpRequest.BodyPublishers.ofString("{}")).build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    /** Drops the connection without an answer while dropNext is positive, then answers 200. */
    private static HttpServer flaky() {
        return server(exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            if (dropNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                exchange.close();
                return;
            }
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    /** Accepts the request and never answers (until the test class ends). */
    private static HttpServer hung() {
        return server(exchange -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
    }

    private static HttpServer validator() {
        return server(exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.createContext("/", handler);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
