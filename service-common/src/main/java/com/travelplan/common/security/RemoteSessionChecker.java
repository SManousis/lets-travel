package com.travelplan.common.security;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Asks auth-service whether a token's session is active; fails closed when auth is unreachable. */
public class RemoteSessionChecker implements SessionChecker {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final URI endpoint;
    private final String serviceToken;

    public RemoteSessionChecker(String authServiceUrl, String serviceToken) {
        this.endpoint = URI.create(authServiceUrl + "/internal/token-validation");
        this.serviceToken = serviceToken;
    }

    @Override
    public Result check(String bearerToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
                    .header("Authorization", "Bearer " + bearerToken)
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
