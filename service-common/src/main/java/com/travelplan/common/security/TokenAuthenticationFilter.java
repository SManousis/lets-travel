package com.travelplan.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates /api requests that carry a bearer token. A request without a token
 * continues unauthenticated, so the service's authorization rules decide whether it
 * is allowed (public endpoints) or rejected with 401.
 *
 * /internal requests must carry the internal service token; anything else is refused with
 * 403 before any controller (or request validation) runs.
 */
public class TokenAuthenticationFilter extends OncePerRequestFilter {
    private final AccessTokenCodec codec;
    private final SessionChecker sessions;
    private final byte[] serviceToken;

    public TokenAuthenticationFilter(AccessTokenCodec codec, SessionChecker sessions, String serviceToken) {
        this.codec = codec;
        this.sessions = sessions;
        this.serviceToken = serviceToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") && !path.startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/internal/")) {
            String presented = request.getHeader("X-Internal-Service-Token");
            if (presented == null || !MessageDigest.isEqual(serviceToken, presented.getBytes(StandardCharsets.UTF_8))) {
                SecurityErrors.write(response, 403, "FORBIDDEN", "Internal endpoint");
                return;
            }
            chain.doFilter(request, response);
            return;
        }
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
