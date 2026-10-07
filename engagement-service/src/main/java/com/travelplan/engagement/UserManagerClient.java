package com.travelplan.engagement;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class UserManagerClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String usersUrl;
    private final String serviceToken;

    UserManagerClient(@Value("${services.users.url:http://127.0.0.1:8082}") String usersUrl,
                      @Value("${security.internal-service-token:change-me-internal}") String serviceToken) {
        this.usersUrl = usersUrl.replaceAll("/+$", "");
        this.serviceToken = serviceToken;
    }

    ManagerSummary get(UUID managerId) {
        HttpResponse<String> response = send(usersUrl + "/internal/managers/" + managerId);
        if (response.statusCode() == 404 || response.statusCode() == 409)
            throw new ManagerPageService.ManagerNotFound();
        if (response.statusCode() != 200) throw new ManagerPageService.DependencyUnavailable();
        try {
            JsonNode body = JSON.readTree(response.body());
            ManagerSummary summary = new ManagerSummary(UUID.fromString(InternalCalls.required(body, "managerId")),
                    InternalCalls.required(body, "displayName"), Instant.parse(InternalCalls.required(body, "memberSince")));
            if (!managerId.equals(summary.managerId())) throw new ManagerPageService.DependencyUnavailable();
            return summary;
        } catch (ManagerPageService.DependencyUnavailable ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ManagerPageService.DependencyUnavailable();
        }
    }

    private HttpResponse<String> send(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                .header("X-Internal-Service-Token", serviceToken).header("X-Request-ID", InternalCalls.requestId()).GET().build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new ManagerPageService.DependencyUnavailable();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ManagerPageService.DependencyUnavailable();
        }
    }

    record ManagerSummary(UUID managerId, String displayName, Instant memberSince) {}
}
