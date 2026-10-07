package com.travelplan.users;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Traveler profiles hold passport data: users see only their own, admins see all, and others get 404. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "security.internal-service-token=test-internal"})
class TravelerOwnershipTest {
    private static final HttpServer AUTH = acceptingAuthServer();
    private static final AccessTokenCodec CODEC = new AccessTokenCodec("replace-with-at-least-32-random-characters", Duration.ofMinutes(5));
    private final JsonMapper json = JsonMapper.builder().build();
    @Value("${local.server.port}") int port;

    private UUID alice;
    private UUID bob;
    private String aliceProfile;

    @DynamicPropertySource
    static void authUrl(DynamicPropertyRegistry registry) {
        registry.add("auth.service-url", () -> "http://127.0.0.1:" + AUTH.getAddress().getPort());
        // Deleting a profile first removes it from travels; the stand-in accepts that cleanup too.
        registry.add("services.travel.url", () -> "http://127.0.0.1:" + AUTH.getAddress().getPort());
    }

    @AfterAll static void stopAuth() { AUTH.stop(0); }

    @BeforeEach void twoUsersWithProfiles() throws Exception {
        alice = register("alice");
        bob = register("bob");
        aliceProfile = createProfile(alice, Role.TRAVELER, alice);
        createProfile(bob, Role.TRAVELER, bob);
    }

    @Test void ownerManagesTheirOwnProfile() throws Exception {
        String asAlice = bearer(alice, Role.TRAVELER);
        assertThat(send("GET", "/api/travelers/" + aliceProfile, null, asAlice).statusCode()).isEqualTo(200);
        assertThat(send("PATCH", "/api/travelers/" + aliceProfile, profileUpdate(), asAlice).statusCode()).isEqualTo(200);
        assertThat(send("DELETE", "/api/travelers/" + aliceProfile, null, asAlice).statusCode()).isEqualTo(204);
    }

    @Test void otherUsersGet404NotTheProfile() throws Exception {
        for (Role role : new Role[] {Role.TRAVELER, Role.TRAVEL_MANAGER}) {
            String asBob = bearer(bob, role);
            HttpResponse<String> read = send("GET", "/api/travelers/" + aliceProfile, null, asBob);
            assertThat(read.statusCode()).as(role + " read").isEqualTo(404);
            assertThat(read.body()).doesNotContain("P-alice");
            assertThat(send("PATCH", "/api/travelers/" + aliceProfile, profileUpdate(), asBob).statusCode()).as(role + " update").isEqualTo(404);
            assertThat(send("DELETE", "/api/travelers/" + aliceProfile, null, asBob).statusCode()).as(role + " delete").isEqualTo(404);
            assertThat(send("POST", "/api/travelers", profile(alice), asBob).statusCode()).as(role + " create for alice").isEqualTo(404);
        }
        assertThat(send("GET", "/api/travelers/" + aliceProfile, null, bearer(alice, Role.TRAVELER)).statusCode())
                .as("alice's profile is untouched").isEqualTo(200);
    }

    @Test void listsShowOnlyTheCallersProfilesWhateverUserTheyAskFor() throws Exception {
        String asBob = bearer(bob, Role.TRAVELER);
        String list = send("GET", "/api/travelers?userId=" + alice, null, asBob).body();
        assertThat(json.readTree(list).path("items")).allSatisfy(item -> assertThat(item.path("userId").asString()).isEqualTo(bob.toString()));
        assertThat(list).doesNotContain(alice.toString());
    }

    @Test void adminsSeeAndManageEveryProfile() throws Exception {
        String asAdmin = bearer(UUID.randomUUID(), Role.ADMIN);
        assertThat(send("GET", "/api/travelers/" + aliceProfile, null, asAdmin).statusCode()).isEqualTo(200);
        String list = send("GET", "/api/travelers?userId=" + alice, null, asAdmin).body();
        assertThat(json.readTree(list).path("items")).isNotEmpty()
                .allSatisfy(item -> assertThat(item.path("userId").asString()).isEqualTo(alice.toString()));
        assertThat(send("POST", "/api/travelers", profile(bob), asAdmin).statusCode()).isEqualTo(201);
    }

    private UUID register(String name) throws Exception {
        String body = "{\"email\":\"" + name + "-" + UUID.randomUUID() + "@example.com\",\"firstName\":\"" + name
                + "\",\"lastName\":\"Test\",\"password\":\"Chosen-Secret-1\"}";
        HttpResponse<String> response = send("POST", "/api/registrations", body, null);
        assertThat(response.statusCode()).isEqualTo(201);
        return UUID.fromString(json.readTree(response.body()).path("userId").asString());
    }

    private String createProfile(UUID caller, Role role, UUID owner) throws Exception {
        HttpResponse<String> response = send("POST", "/api/travelers", profile(owner), bearer(caller, role));
        assertThat(response.statusCode()).isEqualTo(201);
        return json.readTree(response.body()).path("id").asString();
    }

    private static String profile(UUID owner) {
        String name = owner.toString().substring(0, 8);
        return "{\"userId\":\"" + owner + "\",\"passportNumber\":\"P-" + name + "\",\"nationality\":\"GR\"}";
    }

    private static String profileUpdate() {
        return "{\"passportNumber\":\"P-updated\",\"nationality\":\"IT\"}";
    }

    private static String bearer(UUID user, Role role) {
        return "Bearer " + CODEC.issue(user, role, UUID.randomUUID()).value();
    }

    private HttpResponse<String> send(String method, String path, String body, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) request.header("Content-Type", "application/json");
        if (authorization != null) request.header("Authorization", authorization);
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    /** Stands in for auth-service and travel-service: accepts provisioning, token validation and traveler cleanup. */
    private static HttpServer acceptingAuthServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    exchange.getRequestBody().readAllBytes();
                    int status = exchange.getRequestURI().getPath().equals("/internal/identities") ? 201
                            : exchange.getRequestURI().getPath().equals("/internal/token-validation") ? 200 : 204;
                    exchange.sendResponseHeaders(status, -1);
                }
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
