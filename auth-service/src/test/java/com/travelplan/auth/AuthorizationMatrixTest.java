package com.travelplan.auth;

import com.travelplan.common.testing.AuthorizationMatrix;
import com.travelplan.common.testing.AuthorizationMatrix.Access;
import com.travelplan.common.testing.AuthorizationMatrix.Endpoint;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static com.travelplan.common.testing.AuthorizationMatrix.endpoint;
import static org.assertj.core.api.Assertions.assertThat;

/** Every auth-service endpoint, its minimum role, and proof that the rule holds when called directly. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "security.internal-service-token=test-internal"})
class AuthorizationMatrixTest {
    // Logout ends the caller's session, so it is checked last.
    static final List<Endpoint> MATRIX = List.of(
            endpoint("POST", "/api/auth/login", Access.PUBLIC),
            endpoint("POST", "/api/auth/refresh", Access.PUBLIC),
            endpoint("GET", "/api/auth/me", Access.TRAVELER),
            endpoint("POST", "/internal/identities", Access.INTERNAL),
            endpoint("POST", "/internal/identities/{subject}", Access.INTERNAL),
            endpoint("POST", "/internal/identities/{subject}/password", Access.INTERNAL),
            endpoint("POST", "/internal/revoke/{subject}", Access.INTERNAL),
            endpoint("POST", "/internal/token-validation", Access.INTERNAL),
            endpoint("POST", "/api/auth/logout", Access.TRAVELER));

    @Autowired ApplicationContext context;
    @Value("${local.server.port}") int port;

    @Test void matrixListsEveryEndpoint() {
        AuthorizationMatrix.assertCoversAllMappings(context, MATRIX);
    }

    @Test void everyEndpointEnforcesItsRoleWhenCalledDirectly() throws Exception {
        AuthorizationMatrix.assertEnforced(port, Map.of(
                Access.TRAVELER, signedIn("TRAVELER"),
                Access.TRAVEL_MANAGER, signedIn("TRAVEL_MANAGER"),
                Access.ADMIN, signedIn("ADMIN")), MATRIX);
    }

    /** auth-service checks its own session store, so the matrix needs real signed-in sessions. */
    private String signedIn(String role) throws Exception {
        UUID id = UUID.randomUUID();
        String username = "matrix-" + id + "@example.com";
        assertThat(post("/internal/identities", "{\"userId\":\"" + id + "\",\"username\":\"" + username
                + "\",\"password\":\"Matrix-Secret-1\",\"role\":\"" + role + "\"}", true).statusCode()).isEqualTo(201);
        HttpResponse<String> login = post("/api/auth/login", "{\"username\":\"" + username + "\",\"password\":\"Matrix-Secret-1\"}", false);
        return JsonMapper.builder().build().readTree(login.body()).path("accessToken").asString();
    }

    private HttpResponse<String> post(String path, String body, boolean internal) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (internal) request.header("X-Internal-Service-Token", "test-internal");
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
