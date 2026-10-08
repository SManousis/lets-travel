package com.travelplan.engagement;

import com.travelplan.common.ServiceUrls;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads payment figures from payment-service for the dashboards (provider usage, totals per currency). */
@Component
class PaymentInsightsClient {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final String paymentsUrl;
    private final String serviceToken;

    PaymentInsightsClient(@Value("${services.payments.url:http://127.0.0.1:8084}") String paymentsUrl,
                          @Value("${security.internal-service-token:change-me-internal}") String serviceToken) {
        this.paymentsUrl = ServiceUrls.withoutTrailingSlashes(paymentsUrl);
        this.serviceToken = serviceToken;
    }

    List<Usage> usage(java.util.UUID userId) {
        List<Usage> usage = new ArrayList<>();
        for (JsonNode item : get("/internal/users/" + userId + "/payment-usage")) {
            usage.add(new Usage(item.path("provider").asString(), item.path("payments").asLong()));
        }
        return usage;
    }

    List<Totals> totals() {
        List<Totals> totals = new ArrayList<>();
        for (JsonNode item : get("/internal/payment-totals")) {
            totals.add(new Totals(item.path("currency").asString(), item.path("succeededMinor").asLong(),
                    item.path("refundPendingMinor").asLong(), item.path("refundedMinor").asLong()));
        }
        return totals;
    }

    private JsonNode get(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(paymentsUrl + path)).timeout(Duration.ofSeconds(5))
                .header("X-Internal-Service-Token", serviceToken).header("X-Request-ID", InternalCalls.requestId()).GET().build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new Unavailable();
            return json.readTree(response.body());
        } catch (IOException failure) {
            throw new Unavailable();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Unavailable();
        }
    }

    record Usage(String provider, long payments) {}

    record Totals(String currency, long succeededMinor, long refundPendingMinor, long refundedMinor) {}

    static final class Unavailable extends RuntimeException {}
}
