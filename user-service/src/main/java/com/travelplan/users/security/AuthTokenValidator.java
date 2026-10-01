package com.travelplan.users.security;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AuthTokenValidator {
    public enum Result { ACTIVE, DENIED, UNAVAILABLE }

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final URI endpoint;
    private final String serviceToken;

    public AuthTokenValidator(@Value("${auth.service-url:http://127.0.0.1:8081}") String authUrl,
            @Value("${security.internal-service-token:change-me-internal}") String serviceToken) {
        this.endpoint = URI.create(authUrl + "/internal/token-validation");
        this.serviceToken = serviceToken;
    }

    public Result validate(String authorization) {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
                    .header("Authorization", authorization)
                    .header("X-Internal-Service-Token", serviceToken)
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == 200) return Result.ACTIVE;
            if (status == 401 || status == 403) return Result.DENIED;
            return Result.UNAVAILABLE;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Result.UNAVAILABLE;
        } catch (IOException | RuntimeException ex) {
            return Result.UNAVAILABLE;
        }
    }
}
