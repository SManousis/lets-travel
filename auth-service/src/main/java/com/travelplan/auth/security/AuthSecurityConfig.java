package com.travelplan.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class AuthSecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, TokenService tokens) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/api/auth/login", "/api/auth/refresh", "/api/auth/internal/**", "/actuator/**").permitAll()
                        .requestMatchers("/api/**").authenticated().anyRequest().permitAll())
                .addFilterBefore(new BearerFilter(tokens), org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    static final class BearerFilter extends OncePerRequestFilter {
        private final TokenService tokens;
        BearerFilter(TokenService tokens) { this.tokens = tokens; }
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (request.getRequestURI().equals("/api/auth/login") || request.getRequestURI().equals("/api/auth/refresh") || request.getRequestURI().startsWith("/api/auth/internal/")
                    || request.getRequestURI().startsWith("/actuator/") || request.getRequestURI().startsWith("/internal/")) { chain.doFilter(request, response); return; }
            String header = request.getHeader("Authorization");
            TokenService.Claims claims = header != null && header.startsWith("Bearer ") ? tokens.verify(header.substring(7)) : null;
            if (claims == null) { response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required"); return; }
            var authentication = new UsernamePasswordAuthenticationToken(claims.subject(), null,
                    java.util.List.of(new SimpleGrantedAuthority("ROLE_" + claims.role())));
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
            request.setAttribute("travelPlanClaims", claims);
            chain.doFilter(request, response);
        }
    }
}
