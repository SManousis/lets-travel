package com.travelplan.travel.security;

import com.travelplan.common.security.ServiceSecurity;
import com.travelplan.common.security.TokenAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Read access starts at traveler capability. Listing mutations start at travel-manager capability and
 * enforce ownership in the controller; destination catalogue writes remain administrator-only.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, TokenAuthenticationFilter tokens) throws Exception {
        return ServiceSecurity.apply(http, tokens)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**", "/error", "/internal/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/destinations", "/api/destinations/**")
                            .hasRole("TRAVELER")
                        .requestMatchers("/api/destinations", "/api/destinations/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/travels", "/api/travels/**").hasRole("TRAVELER")
                        .requestMatchers("/api/travels", "/api/travels/**").hasRole("TRAVEL_MANAGER")
                        .requestMatchers(HttpMethod.GET, "/api/recommendations").hasRole("TRAVELER")
                        .requestMatchers("/api/admin/recommendations/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .build();
    }
}
