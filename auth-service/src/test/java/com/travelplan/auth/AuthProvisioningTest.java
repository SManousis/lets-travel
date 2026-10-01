package com.travelplan.auth;

import com.travelplan.auth.security.TokenService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "security.internal-service-token=test-internal"})
class AuthProvisioningTest {
    @Value("${local.server.port}") int port;
    @Autowired TokenService tokens;
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void provisionedUserKeepsItsIdAndNonAdminRole() throws Exception {
        UUID id = UUID.randomUUID();
        String username = "operator-" + id + "@example.com";
        assertThat(provision(id, username).statusCode()).isEqualTo(201);

        String accessToken = login(username).accessToken();
        TokenService.Claims claims = tokens.verify(accessToken);
        assertThat(claims.subject()).isEqualTo(id.toString());
        assertThat(claims.role()).isEqualTo("OPERATOR");
    }

    @Test void userDeletionRevokesSessionsAndPreventsNewLogin() throws Exception {
        UUID id = UUID.randomUUID();
        String username = "deleted-" + id + "@example.com";
        provision(id, username);
        String accessToken = login(username).accessToken();

        assertThat(call("/internal/revoke/" + id, "", "test-internal").statusCode()).isEqualTo(204);
        assertThat(tokens.verify(accessToken)).isNull();
        assertThat(loginResponse(username).statusCode()).isEqualTo(401);
    }

    @Test void roleAndEmailChangesRevokeOldTokenAndApplyToNextLogin() throws Exception {
        UUID id = UUID.randomUUID();
        String oldUsername = "old-" + id + "@example.com";
        String newUsername = "new-" + id + "@example.com";
        provision(id, oldUsername);
        String oldToken = login(oldUsername).accessToken();

        String update = "{\"username\":\"" + newUsername + "\",\"role\":\"ADMIN\",\"enabled\":true}";
        assertThat(call("/api/auth/internal/identities/" + id, update, "test-internal").statusCode()).isEqualTo(204);
        assertThat(tokens.verify(oldToken)).isNull();
        assertThat(loginResponse(oldUsername).statusCode()).isEqualTo(401);
        assertThat(tokens.verify(login(newUsername).accessToken()).role()).isEqualTo("ADMIN");

        String newToken = login(newUsername).accessToken();
        String disable = "{\"username\":\"" + newUsername + "\",\"role\":\"ADMIN\",\"enabled\":false}";
        assertThat(call("/api/auth/internal/identities/" + id, disable, "test-internal").statusCode()).isEqualTo(204);
        assertThat(tokens.verify(newToken)).isNull();
        assertThat(loginResponse(newUsername).statusCode()).isEqualTo(401);
    }

    @Test void internalRevocationStillSupportsBootstrapAdminSubject() throws Exception {
        String token = tokens.issue("admin", "ADMIN").value();
        assertThat(call("/internal/revoke/admin", "", "test-internal").statusCode()).isEqualTo(204);
        assertThat(tokens.verify(token)).isNull();
    }

    private HttpResponse<String> provision(UUID id, String username) throws Exception {
        String body = "{\"userId\":\"" + id + "\",\"username\":\"" + username
                + "\",\"password\":\"Strong-Secret-1!\",\"role\":\"OPERATOR\"}";
        return call("/api/auth/internal/provision", body, "test-internal");
    }

    private TokenResponse login(String username) throws Exception {
        HttpResponse<String> response = loginResponse(username);
        assertThat(response.statusCode()).isEqualTo(200);
        return new TokenResponse(json.readTree(response.body()).path("accessToken").asString());
    }

    private HttpResponse<String> loginResponse(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"Strong-Secret-1!\"}";
        return call("/api/auth/login", body, null);
    }

    private HttpResponse<String> call(String path, String body, String serviceToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (serviceToken != null) builder.header("X-Internal-Service-Token", serviceToken);
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private record TokenResponse(String accessToken) {}
}
