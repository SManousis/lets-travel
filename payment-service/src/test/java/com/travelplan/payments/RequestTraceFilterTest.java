package com.travelplan.payments;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class RequestTraceFilterTest {
    @Test
    void logsOneSafeInstanceIdAcrossRequestsWhileKeepingTraceIds() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestTraceFilter.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            RequestTraceFilter filter = new RequestTraceFilter();
            for (String traceId : new String[] {"first-trace", "second-trace"}) {
                MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
                request.addHeader("X-Request-ID", traceId);
                MockHttpServletResponse response = new MockHttpServletResponse();
                filter.doFilter(request, response, new MockFilterChain());
                assertThat(response.getHeader("X-Request-ID")).isEqualTo(traceId);
            }

            assertThat(events.list).hasSize(2);
            var mapper = JsonMapper.builder().build();
            var first = mapper.readTree(events.list.get(0).getFormattedMessage());
            var second = mapper.readTree(events.list.get(1).getFormattedMessage());
            assertThat(first.path("instanceId").asString()).matches("[A-Za-z0-9._-]{1,64}");
            assertThat(second.path("instanceId").asString()).isEqualTo(first.path("instanceId").asString());
            assertThat(first.path("traceId").asString()).isEqualTo("first-trace");
            assertThat(second.path("traceId").asString()).isEqualTo("second-trace");
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }
}
