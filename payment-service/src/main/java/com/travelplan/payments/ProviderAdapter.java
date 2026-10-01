package com.travelplan.payments;

public interface ProviderAdapter {
    String provider();
    ProviderResult connect(String reference, String idempotencyKey);
    void detach(String reference, String idempotencyKey);

    record ProviderResult(String providerReference, String displayMetadata) {}

    final class Rejected extends RuntimeException {
        final String code;
        Rejected(String code) { this.code = code; }
    }

    final class Unavailable extends RuntimeException {
        final String code;
        Unavailable(String code) { this.code = code; }
    }
}
