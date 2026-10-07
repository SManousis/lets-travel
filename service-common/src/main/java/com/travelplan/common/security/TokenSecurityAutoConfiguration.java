package com.travelplan.common.security;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

@AutoConfiguration
public class TokenSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    AccessTokenCodec accessTokenCodec(@Value("${security.token-secret:replace-with-at-least-32-random-characters}") String secret,
            @Value("${security.access-token-ttl-seconds:900}") long ttlSeconds) {
        return new AccessTokenCodec(secret, Duration.ofSeconds(ttlSeconds));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(HttpSecurity.class)
    static class Servlet {

        @Bean
        @ConditionalOnMissingBean
        SessionChecker sessionChecker(@Value("${auth.service-url:http://127.0.0.1:8081}") String authUrl,
                @Value("${security.internal-service-token:change-me-internal}") String serviceToken) {
            return new RemoteSessionChecker(authUrl, serviceToken);
        }

        @Bean
        TokenAuthenticationFilter tokenAuthenticationFilter(AccessTokenCodec codec, SessionChecker sessions,
                @Value("${security.internal-service-token:change-me-internal}") String serviceToken) {
            return new TokenAuthenticationFilter(codec, sessions, serviceToken);
        }

        /** The filter runs inside the security chain only, not a second time as a plain servlet filter. */
        @Bean
        FilterRegistrationBean<TokenAuthenticationFilter> tokenAuthenticationFilterRegistration(TokenAuthenticationFilter filter) {
            FilterRegistrationBean<TokenAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
            registration.setEnabled(false);
            return registration;
        }
    }
}
