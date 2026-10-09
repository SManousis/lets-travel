package com.travelplan.users;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Keeps auth-service's sign-in identities in step with the accounts here: creates them, changes username, role
 * or enabled state, and sets passwords. Every call carries the internal service token and has bounded waits.
 */
@Component
public class AuthProvisionClient {
    private static final String SERVICE_TOKEN_HEADER = "X-Internal-Service-Token";
    private final RestClient client;
    private final String token;

    public AuthProvisionClient(@Value("${auth.service-url:http://auth-service:8081}") String url,
                               @Value("${security.internal-service-token:change-me-internal}") String token) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        requests.setReadTimeout(Duration.ofSeconds(6));
        this.client = RestClient.builder().baseUrl(url).requestFactory(requests).build();
        this.token = token;
    }

    public void provision(UUID userId, String username, String password, String role) {
        client.post().uri("/internal/identities").header(SERVICE_TOKEN_HEADER, token)
                .body(new Request(userId, username, password, role)).retrieve().toBodilessEntity();
    }

    public void update(UUID userId, String username, String role, boolean enabled) {
        client.post().uri("/internal/identities/{id}", userId).header(SERVICE_TOKEN_HEADER, token)
                .body(new UpdateRequest(username, role, enabled)).retrieve().toBodilessEntity();
    }

    public void setPassword(UUID userId, String password) {
        client.post().uri("/internal/identities/{id}/password", userId).header(SERVICE_TOKEN_HEADER, token)
                .body(new PasswordRequest(password)).retrieve().toBodilessEntity();
    }

    record PasswordRequest(String password) {}

    record Request(UUID userId, String username, String password, String role) {}

    record UpdateRequest(String username, String role, boolean enabled) {}
}
