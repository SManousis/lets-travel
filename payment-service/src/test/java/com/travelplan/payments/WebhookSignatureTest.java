package com.travelplan.payments;

import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureTest {
    private static final String SECRET = "whsec_unit_test";
    private static final String BODY = "{\"type\":\"payment_intent.succeeded\"}";
    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");

    @Test void acceptsAFreshCorrectSignature() {
        assertThat(WebhookSignature.valid(SECRET, header(NOW.getEpochSecond(), BODY), BODY, NOW)).isTrue();
        assertThat(WebhookSignature.valid(SECRET, header(NOW.getEpochSecond() - 299, BODY), BODY, NOW)).isTrue();
    }

    @Test void rejectsTamperingReplayWrongSecretAndMissingParts() {
        assertThat(WebhookSignature.valid(SECRET, header(NOW.getEpochSecond(), BODY), BODY + " ", NOW)).isFalse();
        assertThat(WebhookSignature.valid(SECRET, header(NOW.getEpochSecond() - 301, BODY), BODY, NOW)).isFalse();
        assertThat(WebhookSignature.valid("other", header(NOW.getEpochSecond(), BODY), BODY, NOW)).isFalse();
        assertThat(WebhookSignature.valid("", header(NOW.getEpochSecond(), BODY), BODY, NOW)).isFalse();
        assertThat(WebhookSignature.valid(SECRET, "v1=abc", BODY, NOW)).isFalse();
        assertThat(WebhookSignature.valid(SECRET, "t=x,v1=abc", BODY, NOW)).isFalse();
        assertThat(WebhookSignature.valid(SECRET, null, BODY, NOW)).isFalse();
    }

    private static String header(long timestamp, String body) {
        return "t=" + timestamp + ",v1=" + WebhookSignature.sign(SECRET, timestamp + "." + body);
    }
}
