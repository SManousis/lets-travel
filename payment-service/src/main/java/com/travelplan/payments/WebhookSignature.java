package com.travelplan.payments;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies a provider callback the way Stripe signs webhooks: header {@code t=<unix seconds>,v1=<hex>}
 * where v1 = HMAC-SHA256(secret, t + "." + raw body). A wrong signature, a missing secret or a
 * timestamp more than five minutes away (replay protection) is rejected. Comparison is constant-time.
 * See https://docs.stripe.com/webhooks#verify-manually.
 */
final class WebhookSignature {
    static final Duration TOLERANCE = Duration.ofMinutes(5);

    private WebhookSignature() {}

    static boolean valid(String secret, String header, String body, Instant now) {
        if (secret == null || secret.isBlank() || header == null || body == null) return false;
        Long timestamp = null;
        String presented = null;
        for (String part : header.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2) continue;
            if (pair[0].equals("t")) {
                try { timestamp = Long.parseLong(pair[1]); } catch (NumberFormatException ex) { return false; }
            } else if (pair[0].equals("v1") && presented == null) {
                presented = pair[1];
            }
        }
        if (timestamp == null || presented == null) return false;
        if (Duration.between(Instant.ofEpochSecond(timestamp), now).abs().compareTo(TOLERANCE) > 0) return false;
        byte[] expected = sign(secret, timestamp + "." + body).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8));
    }

    /** Hex HMAC-SHA256; also used by tests and the demo script to produce valid signatures. */
    static String sign(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
