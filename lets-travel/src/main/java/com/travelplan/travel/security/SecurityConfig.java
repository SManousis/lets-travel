package com.travelplan.travel.security;

import com.travelplan.common.security.ServiceSecurity;
import com.travelplan.common.security.TokenAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Inherited admin APIs stay ADMIN-only until Phase 2 opens them to other roles.
 * /internal endpoints are not routed by the gateway and check the internal service token themselves.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, TokenAuthenticationFilter tokens) throws Exception {
        return ServiceSecurity.apply(http, tokens)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**", "/error", "/internal/**").permitAll()
                        .requestMatchers("/api/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .build();
    }
}
