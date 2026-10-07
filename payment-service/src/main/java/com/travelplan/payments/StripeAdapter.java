package com.travelplan.payments;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class StripeAdapter implements ProviderAdapter, ChargeProvider {
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

    @Override public boolean configured() {
        return secretKey != null && !secretKey.isBlank()
                && (simulated ? secretKey.startsWith("sk_test_sim_")
                : secretKey.startsWith("sk_test_") && !secretKey.startsWith("sk_test_sim_"));
    }

    /**
     * Off-session PaymentIntent for the saved PaymentMethod, confirmed immediately. The Idempotency-Key
     * header makes Stripe return the first result for a replay, so reconciliation cannot charge twice.
     * See https://docs.stripe.com/api/payment_intents/create and https://docs.stripe.com/api/idempotent_requests.
     */
    @Override public ChargeResult charge(ChargeRequest request) {
        String key = credential();
        try {
            validateReference(request.methodReference());
        } catch (Rejected invalid) {
            return ChargeResult.declined("PAYMENT_METHOD_UNUSABLE");
        }
        var method = SandboxHttp.send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/payment_methods/" + request.methodReference()))
                .timeout(SandboxHttp.timeout()).header("Authorization", "Bearer " + key)
                .header("Accept", "application/json").GET().build());
        if (SandboxHttp.ambiguous(method.statusCode())) throw new Unavailable("PROVIDER_UNAVAILABLE");
        if (method.statusCode() != 200) return ChargeResult.declined("PAYMENT_METHOD_UNUSABLE");
        String customer = SandboxHttp.json(method.body()).path("customer").asString();
        if (customer == null || !customer.startsWith("cus_")) return ChargeResult.declined("PAYMENT_METHOD_UNUSABLE");

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("amount", Long.toString(request.amountMinor()));
        fields.put("currency", request.currency().toLowerCase(Locale.ROOT));
        fields.put("customer", customer);
        fields.put("payment_method", request.methodReference());
        fields.put("confirm", "true");
        fields.put("off_session", "true");
        fields.put("metadata[payment_id]", request.paymentId().toString());
        var response = SandboxHttp.send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/payment_intents"))
                .timeout(SandboxHttp.timeout()).header("Authorization", "Bearer " + key)
                .header("Idempotency-Key", request.idempotencyKey())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(SandboxHttp.form(fields))).build());
        if (SandboxHttp.ambiguous(response.statusCode())) throw new Unavailable("PROVIDER_UNAVAILABLE");
        if (response.statusCode() == 402) return ChargeResult.declined("PAYMENT_DECLINED");
        if (response.statusCode() != 200) return ChargeResult.declined("PAYMENT_REJECTED");
        var intent = SandboxHttp.json(response.body());
        // A success is only accepted if it is about this payment, amount and currency (and not live mode).
        if (!request.paymentId().toString().equals(intent.path("metadata").path("payment_id").asString())
                || intent.path("amount").asLong(-1) != request.amountMinor()
                || !request.currency().equalsIgnoreCase(intent.path("currency").asString())
                || intent.path("livemode").asBoolean(true))
            throw new Unavailable("PROVIDER_INVALID_RESPONSE");
        String id = intent.path("id").asString();
        return switch (intent.path("status").asString()) {
            case "succeeded" -> {
                if (id == null || !id.startsWith("pi_")) throw new Unavailable("PROVIDER_INVALID_RESPONSE");
                yield ChargeResult.approved(id);
            }
            case "requires_action" -> ChargeResult.declined("AUTHENTICATION_REQUIRED");
            case "processing" -> throw new Unavailable("PROVIDER_PENDING");
            default -> ChargeResult.declined("PAYMENT_DECLINED");
        };
    }

    /** Full refund of the PaymentIntent; https://docs.stripe.com/api/refunds/create. */
    @Override public String refund(RefundRequest request) {
        String key = credential();
        var response = SandboxHttp.send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/refunds"))
                .timeout(SandboxHttp.timeout()).header("Authorization", "Bearer " + key)
                .header("Idempotency-Key", request.idempotencyKey())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(SandboxHttp.form(Map.of(
                        "payment_intent", request.chargeReference(),
                        "metadata[payment_id]", request.paymentId().toString())))).build());
        if (SandboxHttp.ambiguous(response.statusCode())) throw new Unavailable("PROVIDER_UNAVAILABLE");
        if (response.statusCode() != 200) throw new Rejected("REFUND_REJECTED");
        var refund = SandboxHttp.json(response.body());
        String status = refund.path("status").asString();
        if (!"succeeded".equals(status) && !"pending".equals(status)) throw new Rejected("REFUND_REJECTED");
        return refund.path("id").asString();
    }

    private String credential() {
        if (!configured()) throw new Unavailable("STRIPE_SANDBOX_CREDENTIAL_MISSING");
        return secretKey;
    }

    private static void validateReference(String reference) {
        if (reference == null || !reference.matches("pm_[A-Za-z0-9]{8,252}"))
            throw new Rejected("INVALID_PROVIDER_REFERENCE");
    }
}
