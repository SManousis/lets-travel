package com.travelplan.gateway;

import com.travelplan.common.security.AccessToken;
import com.travelplan.common.security.AccessTokenCodec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

/**
 * Limits applied before anything else: request body size, and per-caller request rates for the endpoints
 * that are cheap to abuse (search, autocomplete, reports, sign-up, password setup). Sign-in attempts are
 * limited by auth-service itself. A signed-in caller is counted by user ID, anyone else by client address
 * (X-Real-IP from the TLS proxy, which overwrites it).
 *
 * <p>Counters are kept in memory per gateway replica, so with N replicas a caller can reach up to N times the
 * limit. That is enough to stop scripted abuse of one endpoint; a shared store would be needed for exact limits.
 */
@Configuration
public class RequestLimits {
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public RequestLimits() {
        this(Clock.systemUTC());
    }

    RequestLimits(Clock clock) {
        this.clock = clock;
    }

    record Limit(String name, String method, String path, int max, Duration per, boolean byAddress) {
        boolean matches(ServerHttpRequest request) {
            return (method == null || method.equals(request.getMethod().name())) && request.getPath().value().equals(path);
        }
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    WebFilter requestLimitFilter(AccessTokenCodec codec,
                            @Value("${gateway.limits.max-body-bytes:262144}") long maxBodyBytes,
                            @Value("${gateway.limits.search-per-minute:120}") int searches,
                            @Value("${gateway.limits.autocomplete-per-minute:300}") int suggestions,
                            @Value("${gateway.limits.reports-per-hour:20}") int reports,
                            @Value("${gateway.limits.registrations-per-hour:10}") int registrations,
                            @Value("${gateway.limits.password-setups-per-hour:20}") int passwordSetups) {
        List<Limit> limits = List.of(
                new Limit("search", "GET", "/api/search/travels", searches, Duration.ofMinutes(1), false),
                new Limit("autocomplete", "GET", "/api/search/autocomplete", suggestions, Duration.ofMinutes(1), false),
                new Limit("reports", "POST", "/api/reports", reports, Duration.ofHours(1), false),
                new Limit("registrations", "POST", "/api/registrations", registrations, Duration.ofHours(1), true),
                new Limit("password-setup", "POST", "/api/password-setup", passwordSetups, Duration.ofHours(1), true));
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            if (request.getHeaders().getContentLength() > maxBodyBytes) {
                return reject(exchange, HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE", "The request body is too large");
            }
            for (Limit limit : limits) {
                if (!limit.matches(request)) continue;
                Window window = windows.computeIfAbsent(limit.name() + "|" + caller(request, codec, limit.byAddress()),
                        key -> new Window());
                long retryAfter = window.take(limit, clock.millis());
                if (retryAfter > 0) {
                    exchange.getResponse().getHeaders().set("Retry-After", String.valueOf(retryAfter));
                    return reject(exchange, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests. Try again later.");
                }
                if (windows.size() > 50_000) forgetExpired();
            }
            return chain.filter(exchange);
        };
    }

    private static String caller(ServerHttpRequest request, AccessTokenCodec codec, boolean byAddress) {
        if (!byAddress) {
            String header = request.getHeaders().getFirst("Authorization");
            Optional<AccessToken> token = header != null && header.startsWith("Bearer ")
                    ? codec.verify(header.substring(7)) : Optional.empty();
            if (token.isPresent()) return "user:" + token.get().userId();
        }
        String forwarded = request.getHeaders().getFirst("X-Real-IP");
        if (forwarded != null && !forwarded.isBlank()) return "ip:" + forwarded.trim();
        return request.getRemoteAddress() == null ? "ip:unknown" : "ip:" + request.getRemoteAddress().getAddress().getHostAddress();
    }

    private void forgetExpired() {
        long now = clock.millis();
        windows.values().removeIf(window -> window.expired(now));
    }

    static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String traceId = response.getHeaders().getFirst("X-Request-ID");
        byte[] body = ("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"fieldErrors\":[],\"traceId\":\""
                + (traceId == null ? "" : traceId) + "\"}").getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    /** A fixed window: up to {@code max} requests per period, counted from the first one. */
    private static final class Window {
        private long startedAt = -1;
        private long endsAt;
        private int count;

        /** Counts a request; returns 0 when allowed, otherwise the seconds until the window ends. */
        synchronized long take(Limit limit, long now) {
            if (startedAt < 0 || now >= endsAt) {
                startedAt = now;
                endsAt = now + limit.per().toMillis();
                count = 0;
            }
            if (count >= limit.max()) return Math.max(1, (endsAt - now + 999) / 1000);
            count++;
            return 0;
        }

        synchronized boolean expired(long now) {
            return now >= endsAt;
        }
    }
}
