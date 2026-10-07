package com.travelplan.common.security;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class TokenAuthenticationFilterTest {
    private final AccessTokenCodec codec = new AccessTokenCodec("a-test-secret-that-is-at-least-32-bytes-long", Duration.ofMinutes(15));
    private final AtomicReference<SessionChecker.Result> session = new AtomicReference<>(SessionChecker.Result.ACTIVE);
    private final TokenAuthenticationFilter filter = new TokenAuthenticationFilter(codec, token -> session.get());

    @AfterEach void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test void activeTokenAuthenticatesWithInheritedAuthorities() throws Exception {
        UUID user = UUID.randomUUID();
        AtomicReference<Authentication> seen = new AtomicReference<>();
        MockHttpServletResponse response = run(bearer(user, Role.TRAVEL_MANAGER), (req, res) ->
                seen.set(SecurityContextHolder.getContext().getAuthentication()));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(((AccessToken) seen.get().getPrincipal()).userId()).isEqualTo(user);
        assertThat(seen.get().getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_TRAVELER", "ROLE_TRAVEL_MANAGER");
    }

    @Test void requestWithoutTokenContinuesUnauthenticated() throws Exception {
        AtomicReference<Authentication> seen = new AtomicReference<>();
        MockHttpServletResponse response = run(null, (req, res) -> seen.set(SecurityContextHolder.getContext().getAuthentication()));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seen.get()).isNull();
    }

    @Test void invalidTokenIsRejectedWithJsonError() throws Exception {
        MockHttpServletResponse response = run("Bearer not-a-token", (req, res) -> { throw new AssertionError("must not reach"); });
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHENTICATED\"", "\"fieldErrors\":[]");
    }

    @Test void nonBearerSchemeIsRejected() throws Exception {
        assertThat(run("Basic YWRtaW46YWRtaW4=", (req, res) -> { }).getStatus()).isEqualTo(401);
    }

    @Test void revokedSessionIsRejected() throws Exception {
        session.set(SessionChecker.Result.DENIED);
        MockHttpServletResponse response = run(bearer(UUID.randomUUID(), Role.ADMIN), (req, res) -> { });
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Session is no longer active");
    }

    @Test void authOutageFailsClosed() throws Exception {
        session.set(SessionChecker.Result.UNAVAILABLE);
        MockHttpServletResponse response = run(bearer(UUID.randomUUID(), Role.ADMIN), (req, res) -> { });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("DEPENDENCY_UNAVAILABLE");
    }

    @Test void ignoresNonApiPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/token-validation");
        request.addHeader("Authorization", "Bearer not-a-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test void errorBodyCarriesTheRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        request.addHeader("Authorization", "Bearer not-a-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader("X-Request-ID", "trace-42");
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getContentAsString()).contains("\"traceId\":\"trace-42\"");
    }

    private String bearer(UUID user, Role role) {
        return "Bearer " + codec.issue(user, role, UUID.randomUUID()).value();
    }

    private MockHttpServletResponse run(String authorization, jakarta.servlet.FilterChain next) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        if (authorization != null) request.addHeader("Authorization", authorization);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, next);
        return response;
    }
}
