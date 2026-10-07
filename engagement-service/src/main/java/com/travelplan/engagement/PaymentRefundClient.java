package com.travelplan.engagement;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Asks payment-service to refund the payment of a cancelled subscription. payment-service owns the
 * money; this call only hands the request over. It is idempotent there, so repeating it is safe.
 */
@Component
class PaymentRefundClient {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String paymentsUrl;
    private final String serviceToken;

    PaymentRefundClient(@Value("${services.payments.url:http://127.0.0.1:8084}") String paymentsUrl,
                        @Value("${security.internal-service-token:change-me-internal}") String serviceToken) {
        this.paymentsUrl = paymentsUrl.replaceAll("/+$", "");
        this.serviceToken = serviceToken;
    }

    /** Throws DependencyUnavailable unless payment-service accepted (2xx) the request. */
    void requestRefund(UUID subscriptionId) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(paymentsUrl + "/internal/subscriptions/" + subscriptionId + "/refund"))
                .timeout(Duration.ofSeconds(10)).header("X-Internal-Service-Token", serviceToken)
                .header("X-Request-ID", InternalCalls.requestId()).POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response = InternalCalls.send(client, request);
        if (response.statusCode() / 100 != 2) throw new ManagerPageService.DependencyUnavailable();
    }
}
