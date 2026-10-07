package com.travelplan.gateway;

import com.sun.net.httpserver.HttpServer;
import com.travelplan.common.security.AccessTokenCodec;
import com.travelplan.common.security.Role;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway's coarse role rules for every routed path: who is turned away (401/403) and who
 * is forwarded. Every upstream is a stand-in answering 200, so 200 means "forwarded".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "security.internal-service-token=test-internal"})
class GatewayRoleMatrixTest {
    private static final HttpServer UPSTREAM = upstream();
    private static final AccessTokenCodec CODEC = new AccessTokenCodec("replace-with-at-least-32-random-characters", Duration.ofMinutes(5));

    /** Routed path -> minimum role, or null for public paths. */
    private static final Map<String, Role> MATRIX = new LinkedHashMap<>();
    static {
        MATRIX.put("/api/auth/login", null);
        MATRIX.put("/api/auth/refresh", null);
        MATRIX.put("/api/registrations", null);
        MATRIX.put("/api/password-setup", null);
        MATRIX.put("/api/auth/me", Role.TRAVELER);
        MATRIX.put("/api/me", Role.TRAVELER);
        MATRIX.put("/api/travelers", Role.TRAVELER);
        MATRIX.put("/api/users", Role.ADMIN);
        MATRIX.put("/api/travels", Role.TRAVELER);
        MATRIX.put("/api/managers", Role.TRAVELER);
        MATRIX.put("/api/destinations", Role.ADMIN);
        MATRIX.put("/api/payment-methods", Role.ADMIN);
        MATRIX.put("/api/payment-gateways", Role.ADMIN);
    }

    @Autowired RouteDefinitionLocator routes;
    @Value("${local.server.port}") int port;

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        String url = "http://127.0.0.1:" + UPSTREAM.getAddress().getPort();
        for (String service : new String[] {"auth", "users", "travel", "payments", "engagement"}) registry.add("services." + service + ".url", () -> url);
        registry.add("auth.service-url", () -> url);
    }

    @AfterAll static void stopUpstream() { UPSTREAM.stop(0); }

    @Test void matrixCoversEveryRoutedPath() {
        Set<String> routed = new TreeSet<>();
        routes.getRouteDefinitions().collectList().block().forEach(route -> route.getPredicates().stream()
                .filter(predicate -> predicate.getName().equals("Path"))
                .flatMap(predicate -> predicate.getArgs().values().stream())
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .filter(path -> !path.endsWith("/**"))
                .forEach(routed::add));
        Set<String> declared = new TreeSet<>(MATRIX.keySet());
        declared.removeIf(path -> path.startsWith("/api/auth/") && !routed.contains(path));
        routed.removeIf(path -> path.equals("/api/auth"));
        assertThat(declared).as("every routed path needs a gateway role decision").containsAll(routed);
    }

    @Test void gatewayTurnsAwayLowerRolesAndForwardsTheRest() throws Exception {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Role> entry : MATRIX.entrySet()) {
            String path = entry.getKey();
            Role minimum = entry.getValue();
            int anonymous = call(path, null);
            if (minimum == null) {
                if (anonymous != 200) failures.add(path + " anonymous -> " + anonymous + " (expected forwarded)");
                continue;
            }
            if (anonymous != 401) failures.add(path + " anonymous -> " + anonymous + " (expected 401)");
            for (Role role : Role.values()) {
                int status = call(path, CODEC.issue(UUID.randomUUID(), role, UUID.randomUUID()).value());
                int expected = role.includes(minimum) ? 200 : 403;
                if (status != expected) failures.add(path + " as " + role + " -> " + status + " (expected " + expected + ")");
            }
        }
        assertThat(failures).isEmpty();
    }

    private int call(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30)).GET();
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    /** Answers 200 to everything, including the token-validation call. */
    private static HttpServer upstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(200, -1);
                }
            });
            server.start();
            return server;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
