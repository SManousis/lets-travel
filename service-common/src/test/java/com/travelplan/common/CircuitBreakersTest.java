package com.travelplan.common;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CircuitBreakersTest {
    private final MutableClock clock = new MutableClock();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @BeforeEach void freshBreakers() { CircuitBreakers.reset(clock); }

    @AfterEach void realClock() { CircuitBreakers.reset(Clock.systemUTC()); }

    @Test void opensAfterRepeatedUnreachableCallsAndFailsFastUntilTheTrial() throws Exception {
        int port = freePort();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/x")).GET().build();
        for (int i = 0; i < CircuitBreakers.FAILURE_THRESHOLD; i++) {
            assertThatThrownBy(() -> CircuitBreakers.send(client, request, HttpResponse.BodyHandlers.discarding()))
                    .isInstanceOf(ConnectException.class).hasMessageNotContaining("Circuit open");
        }
        assertThat(CircuitBreakers.states()).containsValue(CircuitBreakers.State.OPEN);
        assertThatThrownBy(() -> CircuitBreakers.send(client, request, HttpResponse.BodyHandlers.discarding()))
                .isInstanceOf(ConnectException.class).hasMessageContaining("Circuit open");

        // The dependency comes back; after the open period one trial call closes the circuit.
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> { calls.incrementAndGet(); exchange.sendResponseHeaders(503, -1); exchange.close(); });
        server.start();
        try {
            assertThatThrownBy(() -> CircuitBreakers.send(client, request, HttpResponse.BodyHandlers.discarding()))
                    .hasMessageContaining("Circuit open");
            assertThat(calls).hasValue(0);
            clock.advance(CircuitBreakers.OPEN_FOR);
            assertThat(CircuitBreakers.send(client, request, HttpResponse.BodyHandlers.discarding()).statusCode())
                    .as("an answer, even 503, closes the circuit").isEqualTo(503);
            assertThat(CircuitBreakers.states()).containsValue(CircuitBreakers.State.CLOSED);
        } finally {
            server.stop(0);
        }
    }

    @Test void aFailedTrialOpensTheCircuitAgain() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + freePort() + "/x")).GET().build();
        for (int i = 0; i < CircuitBreakers.FAILURE_THRESHOLD; i++) {
            assertThatThrownBy(() -> CircuitBreakers.send(client, request, HttpResponse.BodyHandlers.discarding()));
        }
        clock.advance(CircuitBreakers.OPEN_FOR);
        assertThatThrownBy(() -> CircuitBreakers.send(client, request, HttpResponse.BodyHandlers.discarding()))
                .hasMessageNotContaining("Circuit open");
        assertThatThrownBy(() -> CircuitBreakers.send(client, request, HttpResponse.BodyHandlers.discarding()))
                .hasMessageContaining("Circuit open");
    }

    @Test void forwardsTheCurrentRequestIdUnlessTheCallerSetOne() throws Exception {
        java.util.List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.add(exchange.getRequestHeaders().getFirst(RequestIds.HEADER));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/x");
            RequestIds.within("req-123", () -> {
                try {
                    CircuitBreakers.send(client, HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.discarding());
                    CircuitBreakers.send(client, HttpRequest.newBuilder(uri).header(RequestIds.HEADER, "own-id").GET().build(),
                            HttpResponse.BodyHandlers.discarding());
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            CircuitBreakers.send(client, HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.discarding());
        } finally {
            server.stop(0);
        }
        assertThat(seen).hasSize(3);
        assertThat(seen.get(0)).isEqualTo("req-123");
        assertThat(seen.get(1)).isEqualTo("own-id");
        assertThat(seen.get(2)).as("outside a request a fresh ID is made").isNotBlank().isNotEqualTo("req-123");
        assertThat(org.slf4j.MDC.get(RequestIds.LOG_KEY)).isNull();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-08T10:00:00Z");
        void advance(Duration by) { now = now.plus(by); }
        @Override public Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }
}
