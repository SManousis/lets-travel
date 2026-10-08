package com.travelplan.auth;

import com.travelplan.auth.security.TokenService;
import com.travelplan.common.security.AccessToken;
import com.travelplan.common.security.Role;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "security.internal-service-token=test-internal",
                "security.admin-username=root@example.com", "security.admin-password=Bootstrap-Secret-1!"})
class AuthProvisioningTest {
    private static final String ORIGIN = "https://localhost:8443";
    private static final String PASSWORD = "Strong-Secret-1!";
    @Value("${local.server.port}") int port;
    @Autowired TokenService tokens;
    @Autowired com.travelplan.auth.security.ProvisionedIdentityStore identities;
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void bootstrapAdminIsARealIdentityWithAUuidSubject() throws Exception {
        AccessToken admin = verify(login("root@example.com", "Bootstrap-Secret-1!"));
        assertThat(admin.role()).isEqualTo(Role.ADMIN);
        assertThat(admin.userId()).isNotNull();
    }

    @Test void provisionedIdentityKeepsItsIdAndRole() throws Exception {
        UUID id = UUID.randomUUID();
        String username = "manager-" + id + "@example.com";
        assertThat(provision(id, username, "TRAVEL_MANAGER").statusCode()).isEqualTo(201);

        AccessToken token = verify(login(username, PASSWORD));
        assertThat(token.userId()).isEqualTo(id);
        assertThat(token.role()).isEqualTo(Role.TRAVEL_MANAGER);
    }

    @Test void passwordsAreStoredAsSaltedBcryptAndOldHashesAreUpgradedAtSignIn() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        provision(first, "first-" + first + "@example.com", "TRAVELER");
        provision(second, "second-" + second + "@example.com", "TRAVELER");
        String hash = identities.findBySubject(first).orElseThrow().passwordHash();
        assertThat(hash).startsWith("$2a$12$").doesNotContain(PASSWORD);
        assertThat(identities.findBySubject(second).orElseThrow().passwordHash()).as("salted per password").isNotEqualTo(hash);

        identities.updatePassword(first, new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode(PASSWORD));
        assertThat(login("first-" + first + "@example.com", PASSWORD).statusCode()).isEqualTo(200);
        assertThat(identities.findBySubject(first).orElseThrow().passwordHash()).startsWith("$2a$12$");
    }

    @Test void legacyOrUnknownRolesAreRejected() throws Exception {
        assertThat(provision(UUID.randomUUID(), "op@example.com", "OPERATOR").statusCode()).isEqualTo(400);
        assertThat(provision(UUID.randomUUID(), "x@example.com", "SUPERUSER").statusCode()).isEqualTo(400);
    }

    @Test void internalIdentityCommandsAreNotReachableUnderApiPaths() throws Exception {
        String body = "{\"userId\":\"" + UUID.randomUUID() + "\",\"username\":\"sneaky@example.com\",\"password\":\"x\",\"role\":\"ADMIN\"}";
        assertThat(post("/api/auth/internal/provision", body, Map.of("X-Internal-Service-Token", "test-internal")).statusCode())
                .isEqualTo(401);
        assertThat(post("/internal/identities", body, Map.of("X-Internal-Service-Token", "wrong")).statusCode()).isEqualTo(403);
    }

    @Test void deletionRevokesSessionsAndPreventsNewLogin() throws Exception {
        UUID id = UUID.randomUUID();
        String username = "deleted-" + id + "@example.com";
        provision(id, username, "TRAVELER");
        String accessToken = accessToken(login(username, PASSWORD));

        assertThat(post("/internal/revoke/" + id, "", Map.of("X-Internal-Service-Token", "test-internal")).statusCode()).isEqualTo(204);
        assertThat(tokens.verify(accessToken)).isEmpty();
        assertThat(login(username, PASSWORD).statusCode()).isEqualTo(401);
    }

    @Test void roleChangesAndDisablingRevokeOldTokensImmediately() throws Exception {
        UUID id = UUID.randomUUID();
        String oldUsername = "old-" + id + "@example.com";
        String newUsername = "new-" + id + "@example.com";
        provision(id, oldUsername, "TRAVELER");
        String travelerToken = accessToken(login(oldUsername, PASSWORD));

        assertThat(updateIdentity(id, newUsername, "TRAVEL_MANAGER", true).statusCode()).isEqualTo(204);
        assertThat(tokens.verify(travelerToken)).isEmpty();
        assertThat(login(oldUsername, PASSWORD).statusCode()).isEqualTo(401);
        String managerToken = accessToken(login(newUsername, PASSWORD));
        assertThat(tokens.verify(managerToken).orElseThrow().role()).isEqualTo(Role.TRAVEL_MANAGER);

        assertThat(updateIdentity(id, newUsername, "TRAVEL_MANAGER", false).statusCode()).isEqualTo(204);
        assertThat(tokens.verify(managerToken)).isEmpty();
        assertThat(login(newUsername, PASSWORD).statusCode()).isEqualTo(401);
    }

    @Test void settingAPasswordEndsOldSessionsAndReplacesTheOldPassword() throws Exception {
        UUID id = UUID.randomUUID();
        String username = "setup-" + id + "@example.com";
        provision(id, username, "TRAVELER");
        String oldToken = accessToken(login(username, PASSWORD));

        HttpResponse<String> set = post("/internal/identities/" + id + "/password", "{\"password\":\"Brand-New-Secret\"}",
                Map.of("X-Internal-Service-Token", "test-internal"));
        assertThat(set.statusCode()).isEqualTo(204);
        assertThat(tokens.verify(oldToken)).isEmpty();
        // New password first: a failed attempt would throttle this username for a few seconds.
        assertThat(login(username, "Brand-New-Secret").statusCode()).isEqualTo(200);
        assertThat(login(username, PASSWORD).statusCode()).isEqualTo(401);
    }

    @Test void settingAPasswordNeedsTheServiceTokenAKnownIdentityAndALongPassword() throws Exception {
        UUID id = UUID.randomUUID();
        provision(id, "rules-" + id + "@example.com", "TRAVELER");
        String path = "/internal/identities/" + id + "/password";
        assertThat(post(path, "{\"password\":\"Brand-New-Secret\"}", Map.of("X-Internal-Service-Token", "wrong")).statusCode()).isEqualTo(403);
        assertThat(post(path, "{\"password\":\"short\"}", Map.of("X-Internal-Service-Token", "test-internal")).statusCode()).isEqualTo(400);
        assertThat(post("/internal/identities/" + UUID.randomUUID() + "/password", "{\"password\":\"Brand-New-Secret\"}",
                Map.of("X-Internal-Service-Token", "test-internal")).statusCode()).isEqualTo(404);
    }

    @Test void passwordsLongerThan72BytesAreRejectedNotCrashed() throws Exception {
        String tooLong = "é".repeat(37); // 37 characters, 74 bytes
        UUID id = UUID.randomUUID();
        String body = "{\"userId\":\"" + id + "\",\"username\":\"long-" + id + "@example.com\",\"password\":\"" + tooLong + "\",\"role\":\"TRAVELER\"}";
        assertThat(post("/internal/identities", body, Map.of("X-Internal-Service-Token", "test-internal")).statusCode()).isEqualTo(400);

        provision(id, "long-" + id + "@example.com", "TRAVELER");
        assertThat(post("/internal/identities/" + id + "/password", "{\"password\":\"" + tooLong + "\"}",
                Map.of("X-Internal-Service-Token", "test-internal")).statusCode()).isEqualTo(400);
        assertThat(login("long-" + id + "@example.com", "x".repeat(100)).statusCode()).isEqualTo(401);
    }

    @Test void loginSetsSecureCookiesAndKeepsTheRefreshTokenOutOfTheBody() throws Exception {
        HttpResponse<String> response = loginAs("TRAVELER");
        List<String> cookies = response.headers().allValues("Set-Cookie");

        assertThat(cookies).anySatisfy(c -> assertThat(c).startsWith("refresh_token=")
                .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/auth"));
        assertThat(cookies).anySatisfy(c -> assertThat(c).startsWith("csrf_token=")
                .contains("Secure", "SameSite=Strict").doesNotContain("HttpOnly"));
        assertThat(response.body()).doesNotContain(cookie(response, "refresh_token"));
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }

    @Test void refreshRotatesWithCookieCsrfHeaderAndAllowedOrigin() throws Exception {
        HttpResponse<String> login = loginAs("TRAVELER");

        HttpResponse<String> refreshed = refresh(cookie(login, "refresh_token"), cookie(login, "csrf_token"), ORIGIN);
        assertThat(refreshed.statusCode()).isEqualTo(200);
        assertThat(verify(refreshed).role()).isEqualTo(Role.TRAVELER);
        assertThat(cookie(refreshed, "refresh_token")).isNotEqualTo(cookie(login, "refresh_token"));
    }

    @Test void refreshRejectsMissingOriginForeignOriginAndBadCsrf() throws Exception {
        HttpResponse<String> login = loginAs("TRAVELER");
        String refresh = cookie(login, "refresh_token");
        String csrf = cookie(login, "csrf_token");

        assertThat(refresh(refresh, csrf, null).statusCode()).isEqualTo(403);
        assertThat(refresh(refresh, csrf, "https://evil.example").statusCode()).isEqualTo(403);
        assertThat(refresh(refresh, "forged", ORIGIN).statusCode()).isEqualTo(401);
        assertThat(refresh(refresh, null, ORIGIN).statusCode()).isEqualTo(401);
        assertThat(refresh(null, csrf, ORIGIN).statusCode()).isEqualTo(401);
    }

    @Test void logoutEndsTheSessionAndClearsCookies() throws Exception {
        HttpResponse<String> login = loginAs("TRAVELER");
        String token = accessToken(login);

        HttpResponse<String> logout = post("/api/auth/logout", "", Map.of("Authorization", "Bearer " + token));
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(logout.headers().allValues("Set-Cookie")).anySatisfy(c -> assertThat(c).startsWith("refresh_token=;").contains("Max-Age=0"));
        assertThat(tokens.verify(token)).isEmpty();
        assertThat(refresh(cookie(login, "refresh_token"), cookie(login, "csrf_token"), ORIGIN).statusCode()).isEqualTo(401);
    }

    @Test void meReturnsTheCallerFromTheVerifiedToken() throws Exception {
        UUID id = UUID.randomUUID();
        String username = "me-" + id + "@example.com";
        provision(id, username, "TRAVEL_MANAGER");
        String token = accessToken(login(username, PASSWORD));

        HttpResponse<String> me = send(HttpRequest.newBuilder(uri("/api/auth/me")).header("Authorization", "Bearer " + token).GET());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(json.readTree(me.body()).path("userId").asString()).isEqualTo(id.toString());
        assertThat(json.readTree(me.body()).path("role").asString()).isEqualTo("TRAVEL_MANAGER");
        assertThat(send(HttpRequest.newBuilder(uri("/api/auth/me")).GET()).statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> loginAs(String role) throws Exception {
        UUID id = UUID.randomUUID();
        String username = role.toLowerCase() + "-" + id + "@example.com";
        provision(id, username, role);
        HttpResponse<String> response = login(username, PASSWORD);
        assertThat(response.statusCode()).isEqualTo(200);
        return response;
    }

    private HttpResponse<String> provision(UUID id, String username, String role) throws Exception {
        String body = "{\"userId\":\"" + id + "\",\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\",\"role\":\"" + role + "\"}";
        return post("/internal/identities", body, Map.of("X-Internal-Service-Token", "test-internal"));
    }

    private HttpResponse<String> updateIdentity(UUID id, String username, String role, boolean enabled) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"role\":\"" + role + "\",\"enabled\":" + enabled + "}";
        return post("/internal/identities/" + id, body, Map.of("X-Internal-Service-Token", "test-internal"));
    }

    private HttpResponse<String> login(String username, String password) throws Exception {
        return post("/api/auth/login", "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}", Map.of());
    }

    private HttpResponse<String> refresh(String refreshToken, String csrf, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/api/auth/refresh")).POST(HttpRequest.BodyPublishers.noBody());
        if (refreshToken != null) request.header("Cookie", "refresh_token=" + refreshToken);
        if (csrf != null) request.header("X-CSRF-Token", csrf);
        if (origin != null) request.header("Origin", origin);
        return send(request);
    }

    private AccessToken verify(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        return tokens.verify(accessToken(response)).orElseThrow();
    }

    private String accessToken(HttpResponse<String> response) throws Exception {
        return json.readTree(response.body()).path("accessToken").asString();
    }

    private static String cookie(HttpResponse<String> response, String name) {
        Map<String, String> values = response.headers().allValues("Set-Cookie").stream()
                .map(c -> c.split(";", 2)[0].split("=", 2))
                .collect(Collectors.toMap(pair -> pair[0], pair -> pair.length > 1 ? pair[1] : "", (a, b) -> b));
        return values.get(name);
    }

    private HttpResponse<String> post(String path, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        return send(builder);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(builder.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
