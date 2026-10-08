package com.travelplan.engagement;

import com.travelplan.common.ServiceUrls;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Reads user-service's safe user summary: display name, member-since and whether the account is active. */
@Component
class UserSummaryClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String usersUrl;
    private final String serviceToken;

    UserSummaryClient(@Value("${services.users.url:http://127.0.0.1:8082}") String usersUrl,
                      @Value("${security.internal-service-token:change-me-internal}") String serviceToken) {
        this.usersUrl = ServiceUrls.withoutTrailingSlashes(usersUrl);
        this.serviceToken = serviceToken;
    }

    /** Empty when the user no longer exists. */
    Optional<UserSummary> find(UUID userId) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(usersUrl + "/internal/users/" + userId + "/summary"))
                .timeout(Duration.ofSeconds(5)).header("X-Internal-Service-Token", serviceToken)
                .header("X-Request-ID", InternalCalls.requestId()).GET().build();
        HttpResponse<String> response = InternalCalls.send(client, request);
        if (response.statusCode() == 404) return Optional.empty();
        if (response.statusCode() != 200) throw new ManagerPageService.DependencyUnavailable();
        try {
            JsonNode body = JSON.readTree(response.body());
            UUID id = UUID.fromString(InternalCalls.required(body, "userId"));
            JsonNode active = body.get("active");
            if (!userId.equals(id) || active == null || !active.isBoolean()) throw new ManagerPageService.DependencyUnavailable();
            return Optional.of(new UserSummary(id, InternalCalls.required(body, "displayName"),
                    Instant.parse(InternalCalls.required(body, "memberSince")), active.asBoolean()));
        } catch (ManagerPageService.DependencyUnavailable ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ManagerPageService.DependencyUnavailable();
        }
    }

    record UserSummary(UUID userId, String displayName, Instant memberSince, boolean active) {}
}
