package com.travelplan.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.time.Duration;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.beans.factory.annotation.Qualifier;
import reactor.netty.http.client.HttpClient;
import reactor.core.publisher.Mono;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.WebFilter;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
public class GatewaySecurityConfig {
    @Bean
    SecurityWebFilterChain gatewaySecurityChain(ServerHttpSecurity http) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange.anyExchange().permitAll())
                .build();
    }

    @Bean
    WebFilter adminTokenFilter(@Value("${security.token-secret:replace-with-at-least-32-random-characters}") String secret,
            @Value("${auth.service-url:http://127.0.0.1:8081}") String authUrl,
            @Value("${security.internal-service-token:change-me-internal}") String internalToken,
            @Qualifier("authValidationHttpClient") HttpClient authValidationHttpClient) {
        if (secret.length() < 32) throw new IllegalArgumentException("security.token-secret must contain at least 32 characters");
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        WebClient client = WebClient.builder().baseUrl(authUrl)
                .clientConnector(new ReactorClientHttpConnector(authValidationHttpClient)).build();
        return (exchange, chain) -> {
            String path = exchange.getRequest().getPath().value();
            if (!path.startsWith("/api/") || path.equals("/api/auth/login") || path.equals("/api/auth/refresh")) return chain.filter(exchange);
            String role = verify(exchange.getRequest().getHeaders().getFirst("Authorization"), key);
            if (role == null) { exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED); return exchange.getResponse().setComplete(); }
            if (!"ADMIN".equals(role)) { exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN); return exchange.getResponse().setComplete(); }
            return client.post().uri("/internal/token-validation")
                    .header("Authorization", exchange.getRequest().getHeaders().getFirst("Authorization"))
                    .header("X-Internal-Service-Token", internalToken)
                    .headers(headers -> {
                        String requestId = exchange.getRequest().getHeaders().getFirst("X-Request-ID");
                        if (requestId != null) headers.set("X-Request-ID", requestId);
                    })
                    .exchangeToMono(response -> {
                        int status = response.statusCode().value();
                        if (status == 200) return Mono.just(ValidationResult.ACTIVE);
                        if (status == 401 || status == 403) return Mono.just(ValidationResult.DENIED);
                        return Mono.just(ValidationResult.UNAVAILABLE);
                    })
                    .timeout(Duration.ofSeconds(3))
                    .onErrorReturn(ValidationResult.UNAVAILABLE)
                    .flatMap(result -> {
                        if (result == ValidationResult.ACTIVE) return chain.filter(exchange);
                        exchange.getResponse().setStatusCode(result == ValidationResult.DENIED
                                ? HttpStatus.UNAUTHORIZED : HttpStatus.SERVICE_UNAVAILABLE);
                        return exchange.getResponse().setComplete();
                    });
        };
    }
    private enum ValidationResult { ACTIVE, DENIED, UNAVAILABLE }
    private String verify(String header, byte[] key) {
        try {
            if (header == null || !header.startsWith("Bearer ")) return null;
            String[] parts = header.substring(7).split("\\.", -1); Base64.Decoder d = Base64.getUrlDecoder();
            if (parts.length != 2 || !MessageDigest.isEqual(sign(parts[0], key), d.decode(parts[1]))) return null;
            String[] values = new String(d.decode(parts[0]), StandardCharsets.UTF_8).split("\\|", -1);
            return values.length == 6 && "travel-plan-auth".equals(values[2]) && "travel-plan-admin".equals(values[3]) && Long.parseLong(values[4]) > System.currentTimeMillis() / 1000 ? values[1] : null;
        } catch (RuntimeException e) { return null; }
    }
    private byte[] sign(String value, byte[] key) {
        try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
