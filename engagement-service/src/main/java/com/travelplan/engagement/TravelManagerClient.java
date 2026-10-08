package com.travelplan.engagement;

import com.travelplan.common.CircuitBreakers;
import com.travelplan.common.ServiceUrls;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class TravelManagerClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String travelUrl;
    private final String serviceToken;

    TravelManagerClient(@Value("${services.travel.url:http://127.0.0.1:8083}") String travelUrl,
                        @Value("${security.internal-service-token:change-me-internal}") String serviceToken) {
        this.travelUrl = ServiceUrls.withoutTrailingSlashes(travelUrl);
        this.serviceToken = serviceToken;
    }

    TravelStatistics get(UUID managerId) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(travelUrl + "/internal/managers/" + managerId
                        + "/travel-statistics"))
                .timeout(Duration.ofSeconds(5)).header("X-Internal-Service-Token", serviceToken)
                .header("X-Request-ID", InternalCalls.requestId()).GET().build();
        try {
            HttpResponse<String> response = CircuitBreakers.send(client, request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new ManagerPageService.DependencyUnavailable();
            JsonNode body = JSON.readTree(response.body());
            UUID responseManager = UUID.fromString(InternalCalls.required(body, "managerId"));
            long completed = requiredCount(body, "completedTravelCount");
            long total = requiredCount(body, "totalTravelCount");
            if (!managerId.equals(responseManager) || completed > total)
                throw new ManagerPageService.DependencyUnavailable();
            return new TravelStatistics(responseManager, completed, total);
        } catch (ManagerPageService.DependencyUnavailable ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new ManagerPageService.DependencyUnavailable();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ManagerPageService.DependencyUnavailable();
        }
    }

    private static long requiredCount(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        if (value == null || !value.isIntegralNumber() || value.asLong() < 0)
            throw new ManagerPageService.DependencyUnavailable();
        return value.asLong();
    }

    record TravelStatistics(UUID managerId, long completedTravelCount, long totalTravelCount) {}
}
