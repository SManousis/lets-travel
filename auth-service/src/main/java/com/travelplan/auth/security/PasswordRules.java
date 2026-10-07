package com.travelplan.auth.security;

import java.nio.charset.StandardCharsets;

/** BCrypt only reads the first 72 bytes of a password and Spring Security rejects longer input. */
public final class PasswordRules {
    public static final int MAX_BYTES = 72;

    private PasswordRules() {}

    public static boolean fits(String password) {
        return password != null && password.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }
}
