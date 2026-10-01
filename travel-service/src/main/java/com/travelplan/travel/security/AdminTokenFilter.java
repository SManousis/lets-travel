package com.travelplan.travel.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AdminTokenFilter extends OncePerRequestFilter {
    private final byte[] secret;
    private final AuthTokenValidator validator;

    public AdminTokenFilter(@Value("${security.token-secret:replace-with-at-least-32-random-characters}") String secret,
            AuthTokenValidator validator) {
        if (secret.length() < 32) throw new IllegalArgumentException("security.token-secret must contain at least 32 characters");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.validator = validator;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/")) { chain.doFilter(request, response); return; }
        Claims claims = verify(request.getHeader("Authorization"));
        if (claims == null) { response.sendError(401, "Authentication required"); return; }
        if (!"ADMIN".equals(claims.role())) { response.sendError(403, "Administrator role required"); return; }
        AuthTokenValidator.Result result = validator.validate(request.getHeader("Authorization"));
        if (result == AuthTokenValidator.Result.DENIED) { response.sendError(401, "Token revoked"); return; }
        if (result == AuthTokenValidator.Result.UNAVAILABLE) { response.sendError(503, "Authentication service unavailable"); return; }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(claims.subject(), null,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        chain.doFilter(request, response);
    }

    private Claims verify(String header) {
        try {
            if (header == null || !header.startsWith("Bearer ")) return null;
            String[] parts = header.substring(7).split("\\.", -1);
            Base64.Decoder decoder = Base64.getUrlDecoder();
            if (parts.length != 2 || !MessageDigest.isEqual(sign(parts[0]), decoder.decode(parts[1]))) return null;
            String[] values = new String(decoder.decode(parts[0]), StandardCharsets.UTF_8).split("\\|", -1);
            if (values.length != 6 || !"travel-plan-auth".equals(values[2]) || !"travel-plan-admin".equals(values[3])
                    || Long.parseLong(values[4]) <= System.currentTimeMillis() / 1000) return null;
            return new Claims(values[0], values[1]);
        } catch (RuntimeException ex) { return null; }
    }

    private byte[] sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private record Claims(String subject, String role) {}
}
