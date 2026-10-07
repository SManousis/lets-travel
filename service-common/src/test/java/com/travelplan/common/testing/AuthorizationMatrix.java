package com.travelplan.common.testing;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks a service's authorization rules endpoint by endpoint, called directly (bypassing the
 * gateway). The matrix must list every endpoint the service exposes, so an endpoint added
 * without an authorization decision fails the build.
 */
public final class AuthorizationMatrix {
    /** Who may call an endpoint. Roles inherit: a higher role may call a lower role's endpoints. */
    public enum Access { PUBLIC, INTERNAL, TRAVELER, TRAVEL_MANAGER, ADMIN }

    public record Endpoint(String method, String path, Access access) {
        String key() {
            return method + " " + path;
        }
    }

    /** Callers to try, lowest first; each maps to an Access level it satisfies. */
    private static final List<Access> ROLES = List.of(Access.TRAVELER, Access.TRAVEL_MANAGER, Access.ADMIN);

    private AuthorizationMatrix() {}

    public static Endpoint endpoint(String method, String path, Access access) {
        return new Endpoint(method, path, access);
    }

    /** Every request mapping of the application appears in the matrix, and nothing else does. */
    public static void assertCoversAllMappings(ApplicationContext context, List<Endpoint> matrix) {
        RequestMappingHandlerMapping mapping = context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        Set<String> actual = new TreeSet<>();
        for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
            for (String path : info.getPatternValues()) {
                if (path.equals("/error")) continue;
                info.getMethodsCondition().getMethods().forEach(method -> actual.add(method.name() + " " + path));
            }
        }
        Set<String> declared = new TreeSet<>(matrix.stream().map(Endpoint::key).toList());
        assertThat(declared).as("authorization matrix must list exactly the service's endpoints").isEqualTo(actual);
    }

    /**
     * For each endpoint: no token gives 401 unless public; every role below the minimum gives 403;
     * the minimum role and above are let through (any status but 401/403). Internal endpoints
     * must refuse a request that lacks the service token, even from an admin.
     */
    public static void assertEnforced(int port, Map<Access, String> bearerTokens, List<Endpoint> matrix) throws Exception {
        List<String> failures = new ArrayList<>();
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (Endpoint endpoint : matrix) {
                String path = endpoint.path().replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
                if (endpoint.access() == Access.INTERNAL) {
                    int asAdmin = call(client, port, endpoint.method(), path, bearerTokens.get(Access.ADMIN)).status();
                    check(failures, endpoint, "admin without service token", asAdmin, asAdmin == 403);
                    continue;
                }
                Result anonymous = call(client, port, endpoint.method(), path, null);
                if (endpoint.access() == Access.PUBLIC) {
                    // Public endpoints may still refuse on their own business rules; only a bearer challenge is wrong.
                    check(failures, endpoint, "anonymous", anonymous.status(), !anonymous.bearerChallenge());
                    continue;
                }
                check(failures, endpoint, "anonymous", anonymous.status(), anonymous.status() == 401);
                for (Access role : ROLES) {
                    int status = call(client, port, endpoint.method(), path, bearerTokens.get(role)).status();
                    boolean allowed = role.ordinal() >= endpoint.access().ordinal();
                    check(failures, endpoint, role.name(), status, allowed ? status != 401 && status != 403 : status == 403);
                }
            }
        }
        assertThat(failures).as("authorization matrix violations").isEmpty();
    }

    private static void check(List<String> failures, Endpoint endpoint, String caller, int status, boolean ok) {
        if (!ok) failures.add(endpoint.key() + " as " + caller + " -> " + status + " (expected " + endpoint.access() + ")");
    }

    private record Result(int status, boolean bearerChallenge) {}

    private static Result call(HttpClient client, int port, String method, String path, String bearer) throws Exception {
        boolean hasBody = method.equals("POST") || method.equals("PUT") || method.equals("PATCH");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, hasBody ? HttpRequest.BodyPublishers.ofString("{}") : HttpRequest.BodyPublishers.noBody());
        if (hasBody) request.header("Content-Type", "application/json");
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        HttpResponse<Void> response = client.send(request.build(), HttpResponse.BodyHandlers.discarding());
        return new Result(response.statusCode(), response.statusCode() == 401
                && response.headers().firstValue("WWW-Authenticate").filter(value -> value.startsWith("Bearer")).isPresent());
    }

    /** A stand-in auth-service that reports every session as active. */
    public static HttpServer activeSessionsAuthServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/token-validation", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(200, -1);
                }
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
