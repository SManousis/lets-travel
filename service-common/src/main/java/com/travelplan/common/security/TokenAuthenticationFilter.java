package com.travelplan.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates /api requests that carry a bearer token. A request without a token
 * continues unauthenticated, so the service's authorization rules decide whether it
 * is allowed (public endpoints) or rejected with 401.
 */
public class TokenAuthenticationFilter extends OncePerRequestFilter {
    private final AccessTokenCodec codec;
    private final SessionChecker sessions;

    public TokenAuthenticationFilter(AccessTokenCodec codec, SessionChecker sessions) {
        this.codec = codec;
        this.sessions = sessions;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null) {
            chain.doFilter(request, response);
            return;
        }
        String raw = header.startsWith("Bearer ") ? header.substring(7) : null;
        Optional<AccessToken> token = codec.verify(raw);
        if (token.isEmpty()) {
            SecurityErrors.write(response, 401, "UNAUTHENTICATED", "Authentication required");
            return;
        }
        SessionChecker.Result session = sessions.check(raw);
        if (session == SessionChecker.Result.DENIED) {
            SecurityErrors.write(response, 401, "UNAUTHENTICATED", "Session is no longer active");
            return;
        }
        if (session == SessionChecker.Result.UNAVAILABLE) {
            SecurityErrors.write(response, 503, "DEPENDENCY_UNAVAILABLE", "Authentication service unavailable");
            return;
        }
        var authorities = token.get().role().authorities().stream().map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(token.get(), null, authorities));
        chain.doFilter(request, response);
    }
}
