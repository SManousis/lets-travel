package com.travelplan.payments;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The built-in TEST provider: a clearly labelled simulation that moves no money and needs no
 * external account, so the payment journey can be demonstrated anywhere. It behaves like a real
 * provider where it matters: it keeps its own ledger keyed by the idempotency key (a replay returns
 * the first result), and the TIMEOUT scenario records the charge but "loses" the first answer,
 * exactly like a network timeout after the provider has charged.
 * Enabled only with {@code payments.test-provider.enabled=true}; never enable it in production.
 */
@Component
class TestPaymentProvider implements ChargeProvider {
    static final String PROVIDER = "TEST";
    private final boolean enabled;
    private final Map<String, ChargeResult> charges = new ConcurrentHashMap<>();
    private final Map<String, Boolean> answered = new ConcurrentHashMap<>();
    private final Map<String, String> refunds = new ConcurrentHashMap<>();
    private final AtomicInteger chargesCreated = new AtomicInteger();

    TestPaymentProvider(@Value("${payments.test-provider.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override public String provider() { return PROVIDER; }

    @Override public boolean configured() { return enabled; }

    @Override public ChargeResult charge(ChargeRequest request) {
        if (!enabled) throw new ProviderAdapter.Unavailable("PROVIDER_NOT_CONFIGURED");
        ChargeResult result = charges.computeIfAbsent(request.idempotencyKey(), key -> {
            chargesCreated.incrementAndGet();
            return switch (request.testScenario()) {
                case "DECLINE" -> ChargeResult.declined("PAYMENT_DECLINED");
                case "APPROVE", "TIMEOUT" -> ChargeResult.approved("test_ch_" + UUID.randomUUID().toString().replace("-", ""));
                default -> throw new ProviderAdapter.Rejected("UNKNOWN_TEST_SCENARIO");
            };
        });
        // TIMEOUT: the charge above is recorded, but the first answer never reaches the caller.
        if ("TIMEOUT".equals(request.testScenario()) && answered.putIfAbsent(request.idempotencyKey(), true) == null)
            throw new ProviderAdapter.Unavailable("PROVIDER_TIMEOUT");
        return result;
    }

    @Override public String refund(RefundRequest request) {
        if (!enabled) throw new ProviderAdapter.Unavailable("PROVIDER_NOT_CONFIGURED");
        if (!charges.containsValue(ChargeResult.approved(request.chargeReference())))
            throw new ProviderAdapter.Rejected("UNKNOWN_CHARGE");
        return refunds.computeIfAbsent(request.idempotencyKey(), key -> "test_re_" + UUID.randomUUID().toString().replace("-", ""));
    }

    /** How many distinct charges this provider has created (tests prove "exactly one"). */
    int chargesCreated() { return chargesCreated.get(); }

    int refundsCreated() { return refunds.size(); }
}
