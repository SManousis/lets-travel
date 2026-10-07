package com.travelplan.travel;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagerIdentityClientTest {
    private static final AtomicInteger STATUS = new AtomicInteger(200);
    private static final AtomicReference<String> BODY = new AtomicReference<>("{}");
    private static final AtomicReference<String> TOKEN = new AtomicReference<>();
    private static final AtomicReference<String> REQUEST_ID = new AtomicReference<>();
    private static final HttpServer SERVER = server();
    private final ManagerIdentityClient client = new ManagerIdentityClient(
            "http://127.0.0.1:" + SERVER.getAddress().getPort(), "internal-secret");

    @AfterAll static void stop() { SERVER.stop(0); }

    @Test void returnsSafeSummaryAndForwardsInternalHeaders() {
        UUID id = UUID.randomUUID();
        BODY.set("{\"managerId\":\"" + id + "\",\"displayName\":\"Maria Manager\","
                + "\"memberSince\":\"2025-03-04T12:30:00Z\"}");
        STATUS.set(200);

        var result = client.requireActiveManager(id);

        assertThat(result.managerId()).isEqualTo(id);
        assertThat(result.displayName()).isEqualTo("Maria Manager");
        assertThat(result.memberSince()).isEqualTo(Instant.parse("2025-03-04T12:30:00Z"));
        assertThat(TOKEN.get()).isEqualTo("internal-secret");
        assertThat(REQUEST_ID.get()).isNotBlank();
    }

    @Test void mapsMissingAndInactiveManagerToStableConflict() {
        for (int status : new int[]{404, 409}) {
            STATUS.set(status);
            var failure = assertThrows(TravelStore.Conflict.class,
                    () -> client.requireActiveManager(UUID.randomUUID()));
            assertThat(failure.code).isEqualTo("MANAGER_NOT_ACTIVE");
        }
    }

    @Test void mapsUpstreamErrorsMalformedBodiesAndConnectionFailuresToDependencyUnavailable() {
        STATUS.set(500);
        assertUnavailable(client);
        STATUS.set(200);
        BODY.set("not-json");
        assertUnavailable(client);
        assertUnavailable(new ManagerIdentityClient("http://127.0.0.1:1", "internal-secret"));
    }

    private static void assertUnavailable(ManagerIdentityClient target) {
        var failure = assertThrows(ManagerIdentityClient.DependencyUnavailable.class,
                () -> target.requireActiveManager(UUID.randomUUID()));
        assertThat(failure.code).isEqualTo("DEPENDENCY_UNAVAILABLE");
    }

    private static HttpServer server() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/managers", exchange -> {
                try (exchange) {
                    TOKEN.set(exchange.getRequestHeaders().getFirst("X-Internal-Service-Token"));
                    REQUEST_ID.set(exchange.getRequestHeaders().getFirst("X-Request-ID"));
                    byte[] response = BODY.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(STATUS.get(), response.length);
                    exchange.getResponseBody().write(response);
                }
            });
            server.start();
            return server;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
