package com.travelplan.common.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Writes the platform error body ({code, message, fieldErrors, traceId}) for security failures. */
public final class SecurityErrors {
    private SecurityErrors() {}

    /** {@code code} and {@code message} are fixed literals chosen by the caller, never user input. */
    public static void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        if (response.isCommitted()) return;
        String traceId = response.getHeader("X-Request-ID");
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (status == HttpServletResponse.SC_UNAUTHORIZED) response.setHeader("WWW-Authenticate", "Bearer");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"fieldErrors\":[],\"traceId\":\""
                + (traceId == null ? "" : traceId) + "\"}");
    }
}
