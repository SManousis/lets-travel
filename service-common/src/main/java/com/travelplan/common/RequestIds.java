package com.travelplan.common;

import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * The request ID (X-Request-ID) that ties together the browser call, the gateway, every service the
 * request reaches, the events it causes and the provider calls it makes. {@link RequestTraceFilter} puts the
 * incoming ID in the logging context for the duration of a request, so every log line carries it, and
 * {@link CircuitBreakers#send} forwards it on outgoing calls.
 */
public final class RequestIds {
    public static final String HEADER = "X-Request-ID";
    public static final String LOG_KEY = "requestId";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private RequestIds() {}

    /** The current request's ID, or a new one for work that no request started (scheduled jobs). */
    public static String current() {
        String id = MDC.get(LOG_KEY);
        return isSafe(id) ? id : UUID.randomUUID().toString();
    }

    /** Only short IDs made of letters, digits, dots, dashes and underscores are accepted, so logs cannot be forged. */
    public static boolean isSafe(String id) {
        return id != null && SAFE_ID.matcher(id).matches();
    }

    /** Runs work (an event relayed from a background job, for example) under the ID of the request that caused it. */
    public static void within(String id, Runnable work) {
        String previous = MDC.get(LOG_KEY);
        if (isSafe(id)) MDC.put(LOG_KEY, id);
        try {
            work.run();
        } finally {
            if (previous == null) MDC.remove(LOG_KEY);
            else MDC.put(LOG_KEY, previous);
        }
    }
}
