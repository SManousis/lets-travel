package com.travelplan.engagement;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;

/** Shared helpers for the bounded internal service clients. */
final class InternalCalls {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private InternalCalls() {}

    static String requestId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String incoming = attributes.getRequest().getHeader("X-Request-ID");
            if (incoming != null && SAFE_ID.matcher(incoming).matches()) return incoming;
        }
        return UUID.randomUUID().toString();
    }

    static String required(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        if (value == null || !value.isString() || value.asString().isBlank())
            throw new ManagerPageService.DependencyUnavailable();
        return value.asString();
    }
}
