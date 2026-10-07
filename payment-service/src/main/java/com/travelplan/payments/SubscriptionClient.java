package com.travelplan.payments;

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
import tools.jackson.databind.json.JsonMapper;

/**
 * engagement-service owns subscriptions. payment-service reads the owner and price snapshot from
 * it before charging, and reports the payment outcome back so the place is confirmed or released.
 * Private /internal endpoints, internal service token, bounded timeouts, X-Request-ID forwarded.
 */
@Component
class SubscriptionClient {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String engagementUrl;
    private final String serviceToken;

    SubscriptionClient(@Value("${services.engagement.url:http://127.0.0.1:8085}") String engagementUrl,
                       @Value("${security.internal-service-token:change-me-internal}") String serviceToken) {
        this.engagementUrl = engagementUrl.replaceAll("/+$", "");
        this.serviceToken = serviceToken;
    }

    record Terms(UUID subscriptionId, UUID travelerId, String status, long amountMinor, String currency,
                 Instant reservationExpiresAt, boolean payable) {}

    /** What engagement-service answered to a payment outcome. */
    enum Delivery { ACCEPTED, REJECTED, UNAVAILABLE }

    /** Throws NotFound for an unknown subscription and DependencyUnavailable when engagement cannot answer. */
    Terms terms(UUID subscriptionId) {
        HttpResponse<String> response = send(request("/internal/subscriptions/" + subscriptionId).GET().build());
        if (response.statusCode() == 404) throw new PaymentDomain.NotFound();
        if (response.statusCode() != 200) throw new SubscriptionPaymentService.DependencyUnavailable();
        try {
            JsonNode body = JSON.readTree(response.body());
            UUID id = UUID.fromString(body.path("subscriptionId").asString());
            String currency = body.path("currency").asString();
            if (!subscriptionId.equals(id) || !currency.matches("[A-Z]{3}") || !body.path("amountMinor").isNumber()
                    || !body.path("payable").isBoolean())
                throw new SubscriptionPaymentService.DependencyUnavailable();
            String expires = body.path("reservationExpiresAt").asString();
            return new Terms(id, UUID.fromString(body.path("travelerId").asString()), body.path("status").asString(),
                    body.path("amountMinor").asLong(), currency, expires == null || expires.isBlank() ? null : Instant.parse(expires),
                    body.path("payable").asBoolean());
        } catch (SubscriptionPaymentService.DependencyUnavailable ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new SubscriptionPaymentService.DependencyUnavailable();
        }
    }

    /** 200 = confirmed/released; 404/409 = engagement refuses (e.g. reservation expired or cancelled meanwhile). */
    Delivery reportOutcome(UUID subscriptionId, boolean succeeded, UUID paymentId) {
        String body = "{\"outcome\":\"" + (succeeded ? "SUCCEEDED" : "FAILED") + "\",\"paymentId\":\"" + paymentId + "\"}";
        try {
            HttpResponse<String> response = send(request("/internal/subscriptions/" + subscriptionId + "/payment-outcome")
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build());
            if (response.statusCode() == 200) return Delivery.ACCEPTED;
            if (response.statusCode() == 404 || response.statusCode() == 409) return Delivery.REJECTED;
            return Delivery.UNAVAILABLE;
        } catch (SubscriptionPaymentService.DependencyUnavailable ex) {
            return Delivery.UNAVAILABLE;
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(engagementUrl + path)).timeout(Duration.ofSeconds(5))
                .header("X-Internal-Service-Token", serviceToken).header("X-Request-ID", requestId());
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new SubscriptionPaymentService.DependencyUnavailable();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SubscriptionPaymentService.DependencyUnavailable();
        }
    }

    private static String requestId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String incoming = attributes.getRequest().getHeader("X-Request-ID");
            if (incoming != null && SAFE_ID.matcher(incoming).matches()) return incoming;
        }
        return UUID.randomUUID().toString();
    }
}
