package com.travelplan.gateway;

import com.travelplan.common.security.AccessToken;
import com.travelplan.common.security.AccessTokenCodec;
import com.travelplan.common.security.Role;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * Coarse authorization at the entry point: verifies the access token, enforces the minimum
 * role for each path prefix, and confirms with auth-service that the session is active.
 * Receiving services repeat these checks and add ownership rules.
 */
@Configuration
public class GatewaySecurityConfig {
    /** Paths that need no token. */
    static final List<String> PUBLIC_PATHS = List.of("/api/auth/login", "/api/auth/refresh");

    /** Minimum role per path prefix, first match wins; other /api paths need any signed-in role. */
    static final List<Rule> RULES = List.of(
            new Rule("/api/auth", Role.TRAVELER),
            new Rule("/api/users", Role.ADMIN),
            new Rule("/api/travelers", Role.ADMIN),
            new Rule("/api/travels", Role.ADMIN),
            new Rule("/api/destinations", Role.ADMIN),
            new Rule("/api/payment-methods", Role.ADMIN),
            new Rule("/api/payment-gateways", Role.ADMIN));

    @Bean
    SecurityWebFilterChain gatewaySecurityChain(ServerHttpSecurity http) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange.anyExchange().permitAll())
                .build();
    }

    @Bean
    WebFilter accessTokenFilter(AccessTokenCodec codec,
            @Value("${auth.service-url:http://127.0.0.1:8081}") String authUrl,
            @Value("${security.internal-service-token:change-me-internal}") String internalToken,
            @Qualifier("authValidationHttpClient") HttpClient authValidationHttpClient) {
        WebClient client = WebClient.builder().baseUrl(authUrl)
                .clientConnector(new ReactorClientHttpConnector(authValidationHttpClient)).build();
        return (exchange, chain) -> {
            String path = exchange.getRequest().getPath().value();
            if (!path.startsWith("/api/") || PUBLIC_PATHS.contains(path)) return chain.filter(exchange);
            String header = exchange.getRequest().getHeaders().getFirst("Authorization");
            Optional<AccessToken> token = header != null && header.startsWith("Bearer ")
                    ? codec.verify(header.substring(7)) : Optional.empty();
            if (token.isEmpty()) return reject(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required");
            if (!token.get().role().includes(requiredRole(path))) {
                return reject(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", "Your role does not allow this action");
            }
            return client.post().uri("/internal/token-validation")
                    .header("Authorization", header)
                    .header("X-Internal-Service-Token", internalToken)
                    .headers(headers -> {
                        String requestId = exchange.getRequest().getHeaders().getFirst("X-Request-ID");
                        if (requestId != null) headers.set("X-Request-ID", requestId);
                    })
                    .exchangeToMono(response -> {
                        int status = response.statusCode().value();
                        if (status == 200) return Mono.just(SessionState.ACTIVE);
                        if (status == 401 || status == 403) return Mono.just(SessionState.DENIED);
                        return Mono.just(SessionState.UNAVAILABLE);
                    })
                    .timeout(Duration.ofSeconds(3))
                    .onErrorReturn(SessionState.UNAVAILABLE)
                    .flatMap(state -> switch (state) {
                        case ACTIVE -> chain.filter(exchange);
                        case DENIED -> reject(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Session is no longer active");
                        case UNAVAILABLE -> reject(exchange, HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                                "Authentication service unavailable");
                    });
        };
    }

    static Role requiredRole(String path) {
        return RULES.stream().filter(rule -> path.equals(rule.prefix()) || path.startsWith(rule.prefix() + "/"))
                .map(Rule::minimum).findFirst().orElse(Role.TRAVELER);
    }

    private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if (status == HttpStatus.UNAUTHORIZED) response.getHeaders().set("WWW-Authenticate", "Bearer");
        String traceId = response.getHeaders().getFirst("X-Request-ID");
        byte[] body = ("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"fieldErrors\":[],\"traceId\":\""
                + (traceId == null ? "" : traceId) + "\"}").getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    record Rule(String prefix, Role minimum) {}

    private enum SessionState { ACTIVE, DENIED, UNAVAILABLE }
}
