package com.travelplan.engagement.security;

import com.travelplan.common.security.ServiceSecurity;
import com.travelplan.common.security.TokenAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Any signed-in role may call the API; each operation adds its own role and ownership checks.
 * /internal endpoints are not routed by the gateway and check the internal service token themselves.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, TokenAuthenticationFilter tokens) throws Exception {
        return ServiceSecurity.apply(http, tokens)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**", "/error", "/internal/**").permitAll()
                        .requestMatchers("/api/admin/**", "/api/dashboards/admin", "/api/dashboards/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/dashboards/manager").hasRole("TRAVEL_MANAGER")
                        .requestMatchers("/api/managed-travels/**").hasRole("TRAVEL_MANAGER")
                        .requestMatchers(HttpMethod.GET, "/api/feedback").hasRole("TRAVEL_MANAGER")
                        .requestMatchers("/api/**").hasRole("TRAVELER")
                        .anyRequest().denyAll())
                .build();
    }
}
