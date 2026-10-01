package com.travelplan.payments;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class PaymentUserClient {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String baseUrl;
    private final String serviceToken;

    PaymentUserClient(@Value("${services.user.url:http://127.0.0.1:8082}") String baseUrl,
                      @Value("${security.internal-service-token:change-me-internal}") String serviceToken) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.serviceToken = serviceToken;
    }

    void requireActive(UUID userId) {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/users/" + userId
                        + "/payment-eligibility"))
                .timeout(Duration.ofSeconds(6)).header("X-Internal-Service-Token", serviceToken).GET().build();
        try {
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == 204) return;
            if (status == 404) throw new PaymentDomain.NotFound();
            if (status == 409) throw new PaymentDomain.Conflict("USER_NOT_ACTIVE");
            throw new ProviderAdapter.Unavailable("USER_SERVICE_UNAVAILABLE");
        } catch (IOException ex) {
            throw new ProviderAdapter.Unavailable("USER_SERVICE_UNAVAILABLE");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ProviderAdapter.Unavailable("USER_SERVICE_UNAVAILABLE");
        }
    }
}
