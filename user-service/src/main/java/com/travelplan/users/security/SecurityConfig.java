package com.travelplan.users.security;

import com.travelplan.common.security.ServiceSecurity;
import com.travelplan.common.security.TokenAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Sign-up and password setup are public; /api/me and traveler profiles are open to every
 * signed-in role (profiles with ownership checks); user administration stays ADMIN-only.
 * /internal endpoints are not routed by the gateway and check the internal service token themselves.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, TokenAuthenticationFilter tokens) throws Exception {
        return ServiceSecurity.apply(http, tokens)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**", "/error", "/internal/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/registrations", "/api/password-setup").permitAll()
                        .requestMatchers("/api/me", "/api/travelers", "/api/travelers/**").hasRole("TRAVELER")
                        .requestMatchers("/api/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .build();
    }
}
