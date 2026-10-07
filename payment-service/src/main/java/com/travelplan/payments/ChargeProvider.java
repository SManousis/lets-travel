package com.travelplan.payments;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

/**
 * A provider that can take a payment and refund it. Implemented by Stripe, PayPal and the built-in
 * TEST provider. The contract every implementation follows:
 * <ul>
 *   <li>The same {@code idempotencyKey} always means the same charge. Sending it again (reconciliation)
 *       returns the first result and never charges twice.</li>
 *   <li>A definite "no" (declined card) is returned as {@code ChargeResult.declined(code)}.</li>
 *   <li>Anything ambiguous (timeout, 5xx, unexpected answer) throws {@link ProviderAdapter.Unavailable}:
 *       the money may or may not have moved, so the caller must reconcile, never charge anew.</li>
 * </ul>
 */
interface ChargeProvider {
    String provider();

    /** False when the provider's credentials are missing; then no payment is even recorded. */
    boolean configured();

    ChargeResult charge(ChargeRequest request);

    /** Returns the provider's refund reference. Unavailable = try again later; Rejected = operator review. */
    String refund(RefundRequest request);

    record ChargeRequest(UUID paymentId, String idempotencyKey, String methodReference, long amountMinor,
                         String currency, String testScenario) {}

    record RefundRequest(UUID paymentId, String idempotencyKey, String chargeReference, long amountMinor, String currency) {}

    /** {@code providerReference} is the verified charge ID when succeeded; {@code failureCode} a safe code when declined. */
    record ChargeResult(boolean succeeded, String providerReference, String failureCode) {
        static ChargeResult approved(String reference) { return new ChargeResult(true, reference, null); }
        static ChargeResult declined(String code) { return new ChargeResult(false, null, code); }
    }

    /** 45050 EUR -> 450.50; 1200 JPY -> 1200. */
    static BigDecimal decimal(long amountMinor, String currency) {
        int digits = Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
        return BigDecimal.valueOf(amountMinor, digits);
    }
}
