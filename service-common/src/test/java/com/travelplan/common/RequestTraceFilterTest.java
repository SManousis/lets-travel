package com.travelplan.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestTraceFilterTest {
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestTraceFilter.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();

    @BeforeEach void captureLogs() {
        events.start();
        logger.addAppender(events);
    }

    @AfterEach void releaseLogs() {
        logger.detachAppender(events);
        events.stop();
    }

    @Test void keepsSafeConfiguredInstanceId() {
        assertThat(new RequestTraceFilter("svc", "pod-1.a_b").instanceId()).isEqualTo("pod-1.a_b");
    }

    @Test void replacesMissingOrUnsafeInstanceIdWithRandomId() {
        assertThat(new RequestTraceFilter("svc", null).instanceId()).matches(UUID_PATTERN);
        assertThat(new RequestTraceFilter("svc", "").instanceId()).matches(UUID_PATTERN);
        assertThat(new RequestTraceFilter("svc", "bad id\"},{").instanceId()).matches(UUID_PATTERN);
    }

    @Test void echoesSafeRequestIdAndLogsOneStructuredLine() throws Exception {
        MockHttpServletResponse response = run(new RequestTraceFilter("engagement-service", "pod-1"), "/api/test", "trace-1", null);

        assertThat(response.getHeader("X-Request-ID")).isEqualTo("trace-1");
        JsonNode line = onlyLogLine();
        assertThat(line.path("service").asString()).isEqualTo("engagement-service");
        assertThat(line.path("instanceId").asString()).isEqualTo("pod-1");
        assertThat(line.path("traceId").asString()).isEqualTo("trace-1");
        assertThat(line.path("operation").asString()).isEqualTo("GET /api/test");
        assertThat(line.path("status").asString()).isEqualTo("200");
    }

    @Test void replacesUnsafeOrMissingRequestId() throws Exception {
        RequestTraceFilter filter = new RequestTraceFilter("svc", "pod-1");
        assertThat(run(filter, "/api/test", "evil\"injected", null).getHeader("X-Request-ID")).matches(UUID_PATTERN);
        assertThat(run(filter, "/api/test", null, null).getHeader("X-Request-ID")).matches(UUID_PATTERN);
        assertThat(events.list).hasSize(2);
    }

    @Test void neverLogsAuthorizationHeader() throws Exception {
        run(new RequestTraceFilter("svc", "pod-1"), "/api/test", "trace-1", "Bearer private-test-token");
        assertThat(events.list.get(0).getFormattedMessage()).doesNotContain("private-test-token");
    }

    @Test void skipsActuatorRequests() throws Exception {
        MockHttpServletResponse response = run(new RequestTraceFilter("svc", "pod-1"), "/actuator/health", "trace-1", null);
        assertThat(response.getHeader("X-Request-ID")).isNull();
        assertThat(events.list).isEmpty();
    }

    @Test void logsEvenWhenTheRequestFails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        MockFilterChain failing = new MockFilterChain() {
            @Override public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) throws ServletException {
                throw new ServletException("boom");
            }
        };
        assertThatThrownBy(() -> new RequestTraceFilter("svc", "pod-1").doFilter(request, new MockHttpServletResponse(), failing))
                .isInstanceOf(ServletException.class);
        assertThat(events.list).hasSize(1);
    }

    private MockHttpServletResponse run(RequestTraceFilter filter, String path, String requestId, String authorization) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (requestId != null) request.addHeader("X-Request-ID", requestId);
        if (authorization != null) request.addHeader("Authorization", authorization);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private JsonNode onlyLogLine() {
        List<ILoggingEvent> lines = events.list;
        assertThat(lines).hasSize(1);
        return JsonMapper.builder().build().readTree(lines.get(0).getFormattedMessage());
    }
}
