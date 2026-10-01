package com.travelplan.payments;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class PayPalAdapter implements ProviderAdapter {
    private static final String REAL_BASE = "https://api-m.sandbox.paypal.com";
    private final String clientId;
    private final String clientSecret;
    private final String baseUrl;
    private final boolean simulated;

    public PayPalAdapter(@Value("${payments.paypal.client-id:}") String clientId,
                         @Value("${payments.paypal.client-secret:}") String clientSecret,
                         @Value("${payments.simulation.enabled:false}") boolean simulated,
                         @Value("${payments.simulation.base-url:}") String simulatorBaseUrl) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.simulated = simulated;
        this.baseUrl = ProviderApiEndpoint.baseUrl(simulated, simulatorBaseUrl, REAL_BASE);
    }

    @Override public String provider() { return "PAYPAL"; }

    @Override public ProviderResult connect(String reference, String idempotencyKey) {
        validateReference(reference);
        var response = SandboxHttp.send(HttpRequest.newBuilder(URI.create(baseUrl + "/v3/vault/payment-tokens/" + reference))
                .timeout(SandboxHttp.timeout()).header("Authorization", "Bearer " + accessToken())
                .header("Accept", "application/json").GET().build());
        SandboxHttp.requireSuccess(response.statusCode());
        var body = SandboxHttp.json(response.body());
        if (!reference.equals(body.path("id").asString())) throw new Rejected("PROVIDER_REFERENCE_MISMATCH");
        if (!body.path("customer").path("id").isTextual()
                || body.path("customer").path("id").asString().isBlank())
            throw new Rejected("METHOD_NOT_VAULTED");
        var card = body.path("payment_source").path("card");
        String brand = SandboxHttp.safeWord(card.path("brand").asString(), "card");
        String lastFour = card.path("last_digits").asString();
        String display = lastFour.matches("[0-9]{4}") ? brand + " •••• " + lastFour : "PAYPAL vault";
        return new ProviderResult(reference, display);
    }

    @Override public void detach(String reference, String idempotencyKey) {
        validateReference(reference);
        var response = SandboxHttp.send(HttpRequest.newBuilder(URI.create(baseUrl + "/v3/vault/payment-tokens/" + reference))
                .timeout(SandboxHttp.timeout()).header("Authorization", "Bearer " + accessToken())
                .DELETE().build());
        if (response.statusCode() == 404) throw new Rejected("PROVIDER_REFERENCE_NOT_FOUND");
        SandboxHttp.requireSuccess(response.statusCode());
    }

    private String accessToken() {
        if (!credentialsAllowed(clientId, clientSecret, simulated))
            throw new Unavailable("PAYPAL_SANDBOX_CREDENTIAL_MISSING");
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret)
                .getBytes(StandardCharsets.UTF_8));
        var response = SandboxHttp.send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/oauth2/token"))
                .timeout(SandboxHttp.timeout()).header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials")).build());
        SandboxHttp.requireSuccess(response.statusCode());
        String token = SandboxHttp.json(response.body()).path("access_token").asString();
        if (token.isBlank()) throw new Unavailable("PROVIDER_INVALID_RESPONSE");
        return token;
    }

    static boolean credentialsAllowed(String clientId, String clientSecret, boolean simulated) {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank())
            return false;
        if (simulated)
            return clientId.startsWith("sim_client_") && clientSecret.startsWith("sim_secret_");
        return !clientId.startsWith("sim_client_") && !clientSecret.startsWith("sim_secret_");
    }

    private static void validateReference(String reference) {
        if (reference == null || !reference.matches("[A-Za-z0-9_-]{7,36}"))
            throw new Rejected("INVALID_PROVIDER_REFERENCE");
    }
}
