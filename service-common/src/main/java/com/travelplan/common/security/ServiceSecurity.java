package com.travelplan.common.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Shared baseline for every servlet service: stateless bearer-token APIs with JSON 401/403
 * errors. CSRF protection is off because these APIs authenticate with bearer headers, never cookies.
 */
public final class ServiceSecurity {
    private ServiceSecurity() {}

    public static HttpSecurity apply(HttpSecurity http, TokenAuthenticationFilter tokens) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(tokens, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, ex) ->
                                SecurityErrors.write(response, 401, "UNAUTHENTICATED", "Authentication required"))
                        .accessDeniedHandler((request, response, ex) ->
                                SecurityErrors.write(response, 403, "FORBIDDEN", "Your role does not allow this action")));
    }
}
