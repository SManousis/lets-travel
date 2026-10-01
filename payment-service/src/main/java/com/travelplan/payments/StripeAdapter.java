package com.travelplan.payments;

import java.net.URI;
import java.net.http.HttpRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class StripeAdapter implements ProviderAdapter {
    private static final String REAL_BASE = "https://api.stripe.com";
    private final String secretKey;
    private final String baseUrl;
    private final boolean simulated;

    public StripeAdapter(@Value("${payments.stripe.secret-key:}") String secretKey,
                         @Value("${payments.simulation.enabled:false}") boolean simulated,
                         @Value("${payments.simulation.base-url:}") String simulatorBaseUrl) {
        this.secretKey = secretKey;
        this.simulated = simulated;
        this.baseUrl = ProviderApiEndpoint.baseUrl(simulated, simulatorBaseUrl, REAL_BASE);
    }

    @Override public String provider() { return "STRIPE"; }

    @Override public ProviderResult connect(String reference, String idempotencyKey) {
        validateReference(reference);
        var response = SandboxHttp.send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/payment_methods/" + reference))
                .timeout(SandboxHttp.timeout()).header("Authorization", "Bearer " + credential())
                .header("Accept", "application/json").GET().build());
        SandboxHttp.requireSuccess(response.statusCode());
        var body = SandboxHttp.json(response.body());
        if (!reference.equals(body.path("id").asString()) || !body.path("livemode").isBoolean()
                || body.path("livemode").asBoolean())
            throw new Rejected("PROVIDER_REFERENCE_MISMATCH");
        if (!body.path("customer").isTextual() || !body.path("customer").asString().startsWith("cus_"))
            throw new Rejected("METHOD_NOT_ATTACHED");
        String type = SandboxHttp.safeWord(body.path("type").asString(), "method");
        String brand = SandboxHttp.safeWord(body.path("card").path("brand").asString(), type);
        String lastFour = body.path("card").path("last4").asString();
        String display = lastFour.matches("[0-9]{4}") ? brand + " •••• " + lastFour : "STRIPE " + type;
        return new ProviderResult(reference, display);
    }

    @Override public void detach(String reference, String idempotencyKey) {
        validateReference(reference);
        String key = credential();
        var inspected = SandboxHttp.send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/payment_methods/" + reference))
                .timeout(SandboxHttp.timeout()).header("Authorization", "Bearer " + key)
                .header("Accept", "application/json").GET().build());
        if (inspected.statusCode() == 404) throw new Rejected("PROVIDER_REFERENCE_NOT_FOUND");
        SandboxHttp.requireSuccess(inspected.statusCode());
        var body = SandboxHttp.json(inspected.body());
        if (!reference.equals(body.path("id").asString()) || !body.path("livemode").isBoolean()
                || body.path("livemode").asBoolean())
            throw new Rejected("PROVIDER_REFERENCE_MISMATCH");
        if (!body.path("customer").isTextual() || body.path("customer").asString().isBlank()) return;
        var detached = SandboxHttp.send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/payment_methods/" + reference + "/detach"))
                .timeout(SandboxHttp.timeout()).header("Authorization", "Bearer " + key)
                .header("Idempotency-Key", idempotencyKey).POST(HttpRequest.BodyPublishers.noBody()).build());
        SandboxHttp.requireSuccess(detached.statusCode());
    }

    private String credential() {
        if (secretKey == null || secretKey.isBlank()
                || (simulated && !secretKey.startsWith("sk_test_sim_"))
                || (!simulated && (!secretKey.startsWith("sk_test_") || secretKey.startsWith("sk_test_sim_"))))
            throw new Unavailable("STRIPE_SANDBOX_CREDENTIAL_MISSING");
        return secretKey;
    }

    private static void validateReference(String reference) {
        if (reference == null || !reference.matches("pm_[A-Za-z0-9]{8,252}"))
            throw new Rejected("INVALID_PROVIDER_REFERENCE");
    }
}
