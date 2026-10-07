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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "security.internal-service-token=test-internal"})
class AccountFlowTest {
    private static final List<String> authCalls = new CopyOnWriteArrayList<>();
    private static final AtomicInteger passwordStatus = new AtomicInteger(204);
    private static final HttpServer AUTH = authServer();
    private static final AccessTokenCodec CODEC = new AccessTokenCodec("replace-with-at-least-32-random-characters", Duration.ofMinutes(5));
    private final JsonMapper json = JsonMapper.builder().build();
    @Value("${local.server.port}") int port;

    @DynamicPropertySource
    static void authUrl(DynamicPropertyRegistry registry) {
        registry.add("auth.service-url", () -> "http://127.0.0.1:" + AUTH.getAddress().getPort());
    }

    @AfterAll static void stopAuth() { AUTH.stop(0); }

    @BeforeEach void reset() {
        authCalls.clear();
        passwordStatus.set(204);
    }

    @Test void signUpCreatesATravelerWithTheChosenPassword() throws Exception {
        String email = unique("new");
        HttpResponse<String> response = post("/api/registrations", registration(email, "Chosen-Secret-1"), null);

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("role").asString()).isEqualTo("TRAVELER");
        assertThat(body.path("email").asString()).isEqualTo(email);
        assertThat(response.body()).doesNotContain("Chosen-Secret-1");
        assertThat(authCalls).anySatisfy(call -> assertThat(call).startsWith("POST /internal/identities ")
                .contains("\"password\":\"Chosen-Secret-1\"", "\"role\":\"TRAVELER\""));
    }

    @Test void signUpCannotChooseAHigherRole() throws Exception {
        String body = registration(unique("sneaky"), "Chosen-Secret-1").replace("}", ",\"role\":\"ADMIN\"}");
        HttpResponse<String> response = post("/api/registrations", body, null);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(json.readTree(response.body()).path("role").asString()).isEqualTo("TRAVELER");
    }

    @Test void signUpRejectsDuplicateEmailsAndShortPasswords() throws Exception {
        String email = unique("dup");
        assertThat(post("/api/registrations", registration(email, "Chosen-Secret-1"), null).statusCode()).isEqualTo(201);
        HttpResponse<String> duplicate = post("/api/registrations", registration(email.toUpperCase(), "Chosen-Secret-1"), null);
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(duplicate.body()).contains("EMAIL_EXISTS");
        assertThat(post("/api/registrations", registration(unique("short"), "short"), null).statusCode()).isEqualTo(400);
    }

    @Test void passwordsAreLimitedTo72BytesAndMalformedJsonIsAValidationError() throws Exception {
        HttpResponse<String> tooLong = post("/api/registrations", registration(unique("long"), "é".repeat(40)), null);
        assertThat(tooLong.statusCode()).isEqualTo(400);
        assertThat(tooLong.body()).contains("VALIDATION_ERROR");
        assertThat(authCalls).isEmpty();
        HttpResponse<String> malformed = post("/api/registrations", "{not json", null);
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(malformed.body()).contains("VALIDATION_ERROR");
    }

    @Test void adminCreatedIdentitiesGetARandomPasswordThatBcryptCanHash() throws Exception {
        setupTokenForNewUser();
        String provisioning = authCalls.stream().filter(call -> call.startsWith("POST /internal/identities ")).findFirst().orElseThrow();
        String password = json.readTree(provisioning.substring(provisioning.indexOf('{'))).path("password").asString();
        assertThat(password.getBytes(StandardCharsets.UTF_8).length).isBetween(32, 72);
    }

    @Test void adminCreatedUserSetsTheirPasswordOnceWithTheLink() throws Exception {
        String token = setupTokenForNewUser();

        assertThat(post("/api/password-setup", setup(token, "Their-Own-Secret"), null).statusCode()).isEqualTo(204);
        assertThat(authCalls).anySatisfy(call -> assertThat(call).matches("POST /internal/identities/[0-9a-f-]{36}/password .*")
                .contains("Their-Own-Secret"));

        HttpResponse<String> reused = post("/api/password-setup", setup(token, "Another-Secret-1"), null);
        assertThat(reused.statusCode()).isEqualTo(400);
        assertThat(reused.body()).contains("INVALID_SETUP_TOKEN");
        assertThat(post("/api/password-setup", setup("made-up-token", "Another-Secret-1"), null).statusCode()).isEqualTo(400);
    }

    @Test void linkSurvivesAnAuthOutageSoTheUserCanRetry() throws Exception {
        String token = setupTokenForNewUser();
        passwordStatus.set(500);
        assertThat(post("/api/password-setup", setup(token, "Their-Own-Secret"), null).statusCode()).isEqualTo(503);

        passwordStatus.set(204);
        assertThat(post("/api/password-setup", setup(token, "Their-Own-Secret"), null).statusCode()).isEqualTo(204);
    }

    @Test void reissuingALinkInvalidatesTheOldOne() throws Exception {
        HttpResponse<String> created = post("/api/users", user(unique("reissue")), bearer(Role.ADMIN, UUID.randomUUID()));
        JsonNode body = json.readTree(created.body());
        String first = body.path("passwordSetup").path("token").asString();

        HttpResponse<String> reissued = post("/api/users/" + body.path("id").asString() + "/password-setup", "", bearer(Role.ADMIN, UUID.randomUUID()));
        assertThat(reissued.statusCode()).isEqualTo(200);
        String second = json.readTree(reissued.body()).path("token").asString();

        assertThat(post("/api/password-setup", setup(first, "Their-Own-Secret"), null).statusCode()).isEqualTo(400);
        assertThat(post("/api/password-setup", setup(second, "Their-Own-Secret"), null).statusCode()).isEqualTo(204);
    }

    @Test void meReturnsTheCallersOwnProfile() throws Exception {
        HttpResponse<String> registered = post("/api/registrations", registration(unique("me"), "Chosen-Secret-1"), null);
        UUID id = UUID.fromString(json.readTree(registered.body()).path("userId").asString());

        HttpResponse<String> me = get("/api/me", bearer(Role.TRAVELER, id));
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(json.readTree(me.body()).path("userId").asString()).isEqualTo(id.toString());
        assertThat(json.readTree(me.body()).path("firstName").asString()).isEqualTo("Ada");
        assertThat(get("/api/me", null).statusCode()).isEqualTo(401);
    }

    @Test void meWorksForTheBootstrapAdminWithoutAProfile() throws Exception {
        UUID admin = UUID.randomUUID();
        HttpResponse<String> me = get("/api/me", bearer(Role.ADMIN, admin));
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(json.readTree(me.body()).path("role").asString()).isEqualTo("ADMIN");
        assertThat(json.readTree(me.body()).path("userId").asString()).isEqualTo(admin.toString());
    }

    @Test void travelersCannotUseUserAdministration() throws Exception {
        String traveler = bearer(Role.TRAVELER, UUID.randomUUID());
        String manager = bearer(Role.TRAVEL_MANAGER, UUID.randomUUID());
        assertThat(get("/api/users", traveler).statusCode()).isEqualTo(403);
        assertThat(get("/api/users", manager).statusCode()).isEqualTo(403);
        assertThat(post("/api/users", user(unique("x")), traveler).statusCode()).isEqualTo(403);
        assertThat(post("/api/users/" + UUID.randomUUID() + "/password-setup", "", manager).statusCode()).isEqualTo(403);
    }

    private String setupTokenForNewUser() throws Exception {
        HttpResponse<String> created = post("/api/users", user(unique("invited")), bearer(Role.ADMIN, UUID.randomUUID()));
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode setup = json.readTree(created.body()).path("passwordSetup");
        assertThat(setup.path("expiresAt").asString()).isNotBlank();
        return setup.path("token").asString();
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private static String registration(String email, String password) {
        return "{\"email\":\"" + email + "\",\"firstName\":\"Ada\",\"lastName\":\"Lovelace\",\"password\":\"" + password + "\"}";
    }

    private static String user(String email) {
        return "{\"email\":\"" + email + "\",\"firstName\":\"Grace\",\"lastName\":\"Hopper\",\"role\":\"TRAVEL_MANAGER\"}";
    }

    private static String setup(String token, String password) {
        return "{\"token\":\"" + token + "\",\"password\":\"" + password + "\"}";
    }

    private static String bearer(Role role, UUID user) {
        return "Bearer " + CODEC.issue(user, role, UUID.randomUUID()).value();
    }

    private HttpResponse<String> post(String path, String body, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) request.header("Authorization", authorization);
        return send(request);
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (authorization != null) request.header("Authorization", authorization);
        return send(request);
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    /** Records identity calls; accepts provisioning and token validation, and answers password calls with passwordStatus. */
    private static HttpServer authServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    String path = exchange.getRequestURI().getPath();
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    authCalls.add(exchange.getRequestMethod() + " " + path + " " + body);
                    boolean trusted = "test-internal".equals(exchange.getRequestHeaders().getFirst("X-Internal-Service-Token"));
                    int status = !trusted ? 403
                            : path.endsWith("/password") ? passwordStatus.get()
                            : path.equals("/internal/identities") ? 201
                            : path.equals("/internal/token-validation") ? 200 : 204;
                    exchange.sendResponseHeaders(status, -1);
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
