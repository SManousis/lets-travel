package com.travelplan.travel;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class ManagerIdentityClient {
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client;
    private final String usersUrl;
    private final String serviceToken;

    ManagerIdentityClient(@Value("${services.users.url:http://127.0.0.1:8082}") String usersUrl,
                          @Value("${security.internal-service-token:change-me-internal}") String serviceToken) {
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        this.usersUrl = usersUrl.replaceAll("/+$", "");
        this.serviceToken = serviceToken;
    }

    ManagerSummary requireActiveManager(UUID managerId) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(usersUrl + "/internal/managers/" + managerId))
                .timeout(Duration.ofSeconds(5))
                .header("X-Internal-Service-Token", serviceToken)
                .header("X-Request-ID", requestId())
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404 || response.statusCode() == 409)
                throw new TravelStore.Conflict("MANAGER_NOT_ACTIVE");
            if (response.statusCode() != 200) throw new DependencyUnavailable("DEPENDENCY_UNAVAILABLE");
            JsonNode body = JSON.readTree(response.body());
            ManagerSummary result = new ManagerSummary(
                    UUID.fromString(required(body, "managerId")),
                    required(body, "displayName"),
                    Instant.parse(required(body, "memberSince")));
            if (!managerId.equals(result.managerId())) throw new DependencyUnavailable("DEPENDENCY_UNAVAILABLE");
            return result;
        } catch (TravelStore.Conflict | DependencyUnavailable ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new DependencyUnavailable("DEPENDENCY_UNAVAILABLE");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new DependencyUnavailable("DEPENDENCY_UNAVAILABLE");
        }
    }

    private static String required(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        if (value == null || !value.isString() || value.asString().isBlank())
            throw new DependencyUnavailable("DEPENDENCY_UNAVAILABLE");
        return value.asString();
    }

    static String requestId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String incoming = attributes.getRequest().getHeader("X-Request-ID");
            if (incoming != null && SAFE_REQUEST_ID.matcher(incoming).matches()) return incoming;
        }
        return UUID.randomUUID().toString();
    }

    record ManagerSummary(UUID managerId, String displayName, Instant memberSince) {}

    static final class DependencyUnavailable extends RuntimeException {
        final String code;
        DependencyUnavailable(String code) { this.code = code; }
    }
}
