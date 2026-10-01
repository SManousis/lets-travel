package com.travelplan.gateway;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;

@Configuration
public class RequestIdFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final String INSTANCE_ID = instanceId();

    private static String instanceId() {
        String configured = System.getenv("SERVICE_INSTANCE_ID");
        if (configured != null && SAFE_ID.matcher(configured).matches()) {
            return configured;
        }
        return UUID.randomUUID().toString();
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    WebFilter requestId() {
        return (exchange, chain) -> {
            String incoming = exchange.getRequest().getHeaders().getFirst("X-Request-ID");
            String id = incoming != null && SAFE_ID.matcher(incoming).matches()
                    ? incoming : UUID.randomUUID().toString();
            ServerWebExchange traced = exchange.mutate()
                    .request(request -> request.headers(headers -> headers.set("X-Request-ID", id)))
                    .build();
            traced.getResponse().getHeaders().set("X-Request-ID", id);
            return chain.filter(traced).doFinally(signal -> log.info(
                    "{\"timestamp\":\"{}\",\"service\":\"api-gateway\",\"instanceId\":\"{}\",\"severity\":\"INFO\",\"traceId\":\"{}\",\"operation\":\"{}\",\"status\":\"{}\"}",
                    Instant.now(), INSTANCE_ID, id, traced.getRequest().getMethod() + " " + traced.getRequest().getPath(),
                    traced.getResponse().getStatusCode() == null ? 0 : traced.getResponse().getStatusCode().value()));
        };
    }
}
