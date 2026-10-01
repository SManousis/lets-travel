package com.travelplan.auth;

import com.travelplan.auth.security.TokenService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "security.internal-service-token=service-secret"})
class InternalTokenValidationControllerTest {
    @Value("${local.server.port}") int port;
    @Autowired TokenService tokens;

    @Test void activeTokenReturnsSubjectAndRole() throws Exception {
        String token = tokens.issue("admin-1", "ADMIN").value();
        HttpResponse<String> response = request(token, "service-secret");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("admin-1", "ADMIN");
    }

    @Test void revokedTokenIsDenied() throws Exception {
        String token = tokens.issue("admin-2", "ADMIN").value();
        tokens.revoke(token);
        assertThat(request(token, "service-secret").statusCode()).isEqualTo(401);
    }

    @Test void wrongInternalCredentialIsDenied() throws Exception {
        String token = tokens.issue("admin-3", "ADMIN").value();
        assertThat(request(token, "wrong-secret").statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> request(String token, String serviceSecret) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/token-validation"))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + token)
                .header("X-Internal-Service-Token", serviceSecret)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
