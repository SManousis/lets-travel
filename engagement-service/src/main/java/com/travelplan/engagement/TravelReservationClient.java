package com.travelplan.engagement;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * travel-service owns travel capacity. Places are reserved and released there, atomically against
 * the participant count in Neo4j, through its private /internal/travels endpoints.
 */
@Component
class TravelReservationClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> RESERVATION_CONFLICTS = Set.of("TRAVEL_NOT_BOOKABLE", "TRAVEL_FULL", "TRAVELER_REMOVED");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String travelUrl;
    private final String serviceToken;

    TravelReservationClient(@Value("${services.travel.url:http://127.0.0.1:8083}") String travelUrl,
                            @Value("${security.internal-service-token:change-me-internal}") String serviceToken) {
        this.travelUrl = travelUrl.replaceAll("/+$", "");
        this.serviceToken = serviceToken;
    }

    TravelSnapshot summary(UUID travelId) {
        HttpResponse<String> response = InternalCalls.send(client, request("/internal/travels/" + travelId).GET().build());
        if (response.statusCode() == 404) throw new SubscriptionService.Failure(404, "NOT_FOUND", "Travel not found");
        if (response.statusCode() != 200) throw new ManagerPageService.DependencyUnavailable();
        return snapshot(travelId, response.body());
    }

    TravelSnapshot reserve(UUID travelId, UUID travelerId) {
        HttpResponse<String> response = InternalCalls.send(client, request("/internal/travels/" + travelId + "/reservations")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"travelerId\":\"" + travelerId + "\"}")).build());
        if (response.statusCode() == 404) throw new SubscriptionService.Failure(404, "NOT_FOUND", "Travel not found");
        if (response.statusCode() == 409) {
            String code = conflictCode(response.body());
            throw new SubscriptionService.Failure(409, code, switch (code) {
                case "TRAVEL_FULL" -> "No places are left on this travel";
                case "TRAVELER_REMOVED" -> "This account cannot book travels";
                default -> "This travel is not open for booking";
            });
        }
        if (response.statusCode() != 200) throw new ManagerPageService.DependencyUnavailable();
        return snapshot(travelId, response.body());
    }

    /** Gives the place back. Idempotent on travel-service's side. */
    void release(UUID travelId, UUID travelerId) {
        HttpResponse<String> response = InternalCalls.send(client,
                request("/internal/travels/" + travelId + "/reservations/" + travelerId).DELETE().build());
        if (response.statusCode() != 204) throw new ManagerPageService.DependencyUnavailable();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(travelUrl + path)).timeout(Duration.ofSeconds(5))
                .header("X-Internal-Service-Token", serviceToken).header("X-Request-ID", InternalCalls.requestId());
    }

    private static String conflictCode(String body) {
        try {
            JsonNode code = JSON.readTree(body).get("code");
            if (code != null && code.isString() && RESERVATION_CONFLICTS.contains(code.asString())) return code.asString();
        } catch (RuntimeException ignored) {
            // Falls through to a dependency failure below.
        }
        throw new ManagerPageService.DependencyUnavailable();
    }

    private static TravelSnapshot snapshot(UUID travelId, String raw) {
        try {
            JsonNode body = JSON.readTree(raw);
            UUID id = UUID.fromString(InternalCalls.required(body, "travelId"));
            String zone = InternalCalls.required(body, "timeZone");
            ZoneId.of(zone);
            JsonNode price = body.get("price");
            if (!travelId.equals(id) || price == null || !price.isNumber()) throw new ManagerPageService.DependencyUnavailable();
            String currency = InternalCalls.required(body, "currency");
            if (!currency.matches("[A-Z]{3}")) throw new ManagerPageService.DependencyUnavailable();
            return new TravelSnapshot(id, UUID.fromString(InternalCalls.required(body, "managerId")),
                    InternalCalls.required(body, "title"), LocalDate.parse(InternalCalls.required(body, "startDate")),
                    LocalDate.parse(InternalCalls.required(body, "endDate")), zone, price.decimalValue(), currency);
        } catch (ManagerPageService.DependencyUnavailable ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ManagerPageService.DependencyUnavailable();
        }
    }

    record TravelSnapshot(UUID travelId, UUID managerId, String title, LocalDate startDate, LocalDate endDate,
                          String timeZone, BigDecimal price, String currency) {}
}
