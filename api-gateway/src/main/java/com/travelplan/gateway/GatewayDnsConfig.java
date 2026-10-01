package com.travelplan.gateway;

import java.time.Duration;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.netty.http.client.HttpClient;

@Configuration
public class GatewayDnsConfig {
    @Bean
    HttpClientCustomizer refreshServiceAddresses() {
        return GatewayDnsConfig::shortLivedResolver;
    }

    @Bean("authValidationHttpClient")
    HttpClient authValidationHttpClient() {
        return shortLivedResolver(HttpClient.create());
    }

    private static HttpClient shortLivedResolver(HttpClient client) {
        return client.resolver(resolver -> resolver.cacheMaxTimeToLive(Duration.ofSeconds(5)));
    }
}
