package com.travelplan.users;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RevocationClient {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String baseUrl;
    private final String token;

    public RevocationClient(@Value("${services.auth.url:http://auth-service:8081}") String url,
                            @Value("${security.internal-service-token:change-me-internal}") String token) {
        this.baseUrl = url.replaceAll("/+$", "");
        this.token = token;
    }

    public void revoke(UUID userId) {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/revoke/" + userId))
                .timeout(Duration.ofSeconds(6)).header("X-Internal-Service-Token", token)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        try {
            if (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() != 204)
                throw new UserStore.Conflict("REVOCATION_UNAVAILABLE");
        } catch (IOException ex) {
            throw new UserStore.Conflict("REVOCATION_UNAVAILABLE");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new UserStore.Conflict("REVOCATION_UNAVAILABLE");
        }
    }
}
