package com.travelplan.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Propagates a safe X-Request-ID, keeps it in the logging context while the request runs ({@link RequestIds})
 * and writes one structured log line per request.
 * Never logs headers, so bearer tokens cannot leak into logs.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestTraceFilter.class);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final String serviceName;
    private final String instanceId;

    public RequestTraceFilter(String serviceName, String configuredInstanceId) {
        this.serviceName = serviceName;
        this.instanceId = isSafe(configuredInstanceId) ? configuredInstanceId : UUID.randomUUID().toString();
    }

    static boolean isSafe(String id) {
        return id != null && SAFE_ID.matcher(id).matches();
    }

    String instanceId() {
        return instanceId;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(RequestIds.HEADER);
        String id = isSafe(incoming) ? incoming : UUID.randomUUID().toString();
        response.setHeader(RequestIds.HEADER, id);
        MDC.put(RequestIds.LOG_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            log.info("{\"timestamp\":\"{}\",\"service\":\"{}\",\"instanceId\":\"{}\",\"severity\":\"INFO\",\"traceId\":\"{}\",\"operation\":\"{}\",\"status\":\"{}\"}",
                    Instant.now(), serviceName, instanceId, id, request.getMethod() + " " + request.getRequestURI(), response.getStatus());
            MDC.remove(RequestIds.LOG_KEY);
        }
    }
}
