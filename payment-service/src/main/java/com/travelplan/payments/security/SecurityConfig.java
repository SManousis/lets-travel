package com.travelplan.payments.security;

import com.travelplan.common.security.ServiceSecurity;
import com.travelplan.common.security.TokenAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Gateway and saved-method administration stay ADMIN-only. Any signed-in user may pay for their own
 * subscription (ownership is checked in SubscriptionPaymentService). Provider webhooks are public and
 * trusted only through their verified signature. /internal endpoints are not routed by the gateway and
 * require the internal service token.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, TokenAuthenticationFilter tokens) throws Exception {
        return ServiceSecurity.apply(http, tokens)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**", "/error", "/internal/**", "/api/payment-webhooks/*").permitAll()
                        .requestMatchers("/api/subscription-payments", "/api/subscription-payments/*", "/api/payment-options")
                        .hasRole("TRAVELER")
                        .requestMatchers("/api/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .build();
    }
}
