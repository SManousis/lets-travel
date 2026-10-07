package com.travelplan.payments;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class PayPalAdapter implements ProviderAdapter, ChargeProvider {
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

    @Override public boolean configured() { return credentialsAllowed(clientId, clientSecret, simulated); }

    /**
     * Orders v2 with the vaulted card and intent CAPTURE, which PayPal captures straight away. The
     * PayPal-Request-Id header makes a replay return the first order instead of creating another.
     * See https://developer.paypal.com/docs/api/orders/v2/#orders_create and
     * https://developer.paypal.com/api/rest/reference/idempotency/.
     */
    @Override public ChargeResult charge(ChargeRequest request) {
        try {
            validateReference(request.methodReference());
        } catch (Rejected invalid) {
            return ChargeResult.declined("PAYMENT_METHOD_UNUSABLE");
        }
        BigDecimal value = ChargeProvider.decimal(request.amountMinor(), request.currency());
        String body = SandboxHttp.write(Map.of(
                "intent", "CAPTURE",
                "purchase_units", List.of(Map.of("custom_id", request.paymentId().toString(),
                        "amount", Map.of("currency_code", request.currency(), "value", value.toPlainString()))),
                "payment_source", Map.of("card", Map.of("vault_id", request.methodReference()))));
        var response = SandboxHttp.send(HttpRequest.newBuilder(URI.create(baseUrl + "/v2/checkout/orders"))
                .timeout(SandboxHttp.timeout()).header("Authorization", "Bearer " + accessToken())
                .header("PayPal-Request-Id", request.idempotencyKey()).header("Prefer", "return=representation")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build());
        if (SandboxHttp.ambiguous(response.statusCode())) throw new Unavailable("PROVIDER_UNAVAILABLE");
        if (response.statusCode() == 422) return ChargeResult.declined("PAYMENT_DECLINED");
        if (response.statusCode() != 200 && response.statusCode() != 201) return ChargeResult.declined("PAYMENT_REJECTED");
        var unit = SandboxHttp.json(response.body()).path("purchase_units").path(0);
        var capture = unit.path("payments").path("captures").path(0);
        if (!request.paymentId().toString().equals(unit.path("custom_id").asString())) throw new Unavailable("PROVIDER_INVALID_RESPONSE");
        String status = capture.path("status").asString();
        if ("DECLINED".equals(status) || "FAILED".equals(status)) return ChargeResult.declined("PAYMENT_DECLINED");
        if (!"COMPLETED".equals(status)) throw new Unavailable("PROVIDER_PENDING");
        // A success is only accepted for this exact amount and currency.
        try {
            if (new BigDecimal(capture.path("amount").path("value").asString()).compareTo(value) != 0
                    || !request.currency().equals(capture.path("amount").path("currency_code").asString()))
                throw new Unavailable("PROVIDER_INVALID_RESPONSE");
        } catch (NumberFormatException ex) {
            throw new Unavailable("PROVIDER_INVALID_RESPONSE");
        }
        String captureId = capture.path("id").asString();
        if (captureId == null || captureId.isBlank()) throw new Unavailable("PROVIDER_INVALID_RESPONSE");
        return ChargeResult.approved(captureId);
    }

    /** Full refund of the capture; https://developer.paypal.com/docs/api/payments/v2/#captures_refund. */
    @Override public String refund(RefundRequest request) {
        if (request.chargeReference() == null || !request.chargeReference().matches("[A-Za-z0-9_-]{1,64}"))
            throw new Rejected("REFUND_REJECTED");
        var response = SandboxHttp.send(HttpRequest.newBuilder(URI.create(baseUrl + "/v2/payments/captures/"
                        + request.chargeReference() + "/refund"))
                .timeout(SandboxHttp.timeout()).header("Authorization", "Bearer " + accessToken())
                .header("PayPal-Request-Id", request.idempotencyKey()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build());
        if (SandboxHttp.ambiguous(response.statusCode())) throw new Unavailable("PROVIDER_UNAVAILABLE");
        if (response.statusCode() != 200 && response.statusCode() != 201) throw new Rejected("REFUND_REJECTED");
        var refund = SandboxHttp.json(response.body());
        String status = refund.path("status").asString();
        if (!"COMPLETED".equals(status) && !"PENDING".equals(status)) throw new Rejected("REFUND_REJECTED");
        return refund.path("id").asString();
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
