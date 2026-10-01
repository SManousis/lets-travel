package com.travelplan.gateway;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {
    @Test
    void logsOneSafeInstanceIdAcrossRequestsWhileKeepingTraceIds() {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestIdFilter.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            var filter = new RequestIdFilter().requestId();
            for (String traceId : new String[] {"first-trace", "second-trace"}) {
                var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test")
                        .header("X-Request-ID", traceId));
                filter.filter(exchange, ignored -> Mono.empty()).block();
                assertThat(exchange.getResponse().getHeaders().getFirst("X-Request-ID")).isEqualTo(traceId);
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
