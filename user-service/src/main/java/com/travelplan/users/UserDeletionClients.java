package com.travelplan.users;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class UserDeletionClients {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String paymentUrl;
    private final String travelUrl;
    private final String serviceToken;

    UserDeletionClients(@Value("${services.payment.url:http://127.0.0.1:8084}") String paymentUrl,
                        @Value("${services.travel.url:http://127.0.0.1:8083}") String travelUrl,
                        @Value("${security.internal-service-token:change-me-internal}") String serviceToken) {
        this.paymentUrl = paymentUrl.replaceAll("/+$", "");
        this.travelUrl = travelUrl.replaceAll("/+$", "");
        this.serviceToken = serviceToken;
    }

    boolean cleanupPayments(UUID userId) {
        var request = HttpRequest.newBuilder(URI.create(paymentUrl + "/internal/users/" + userId
                        + "/payment-cleanup"))
                .timeout(Duration.ofSeconds(30)).header("X-Internal-Service-Token", serviceToken)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        int status = send(request);
        if (status == 204) return true;
        if (status == 202) return false;
        throw new Unavailable("PAYMENT_CLEANUP_UNAVAILABLE");
    }

    void cleanupTravelers(List<UUID> travelerIds) {
        String body = travelerIds.stream().map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        var request = HttpRequest.newBuilder(URI.create(travelUrl + "/internal/travelers/cleanup"))
                .timeout(Duration.ofSeconds(15)).header("X-Internal-Service-Token", serviceToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        if (send(request) != 204) throw new Unavailable("TRAVEL_CLEANUP_UNAVAILABLE");
    }

    private int send(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException ex) {
            throw new Unavailable("CLEANUP_DEPENDENCY_UNAVAILABLE");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Unavailable("CLEANUP_DEPENDENCY_UNAVAILABLE");
        }
    }

    static final class Unavailable extends RuntimeException {
        final String code;
        Unavailable(String code) { this.code = code; }
    }
}
