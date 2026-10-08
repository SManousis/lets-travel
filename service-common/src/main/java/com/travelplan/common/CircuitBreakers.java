package com.travelplan.common;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One circuit breaker per dependency (host and port), shared by every client in the service.
 * After {@value #FAILURE_THRESHOLD} consecutive calls that could not reach the dependency (connection
 * refused or timed out), calls fail at once for {@link #OPEN_FOR} instead of each waiting for its own
 * timeout. Then one trial call is let through: success closes the circuit, failure opens it again.
 *
 * <p>Only unreachable dependencies count. A dependency that answers, even with 503, answers quickly,
 * so it cannot cause cascading waits. A refused call throws {@link ConnectException}, which every
 * client already treats as "dependency unavailable".
 */
public final class CircuitBreakers {
    static final int FAILURE_THRESHOLD = 5;
    static final Duration OPEN_FOR = Duration.ofSeconds(15);
    private static final Logger log = LoggerFactory.getLogger(CircuitBreakers.class);
    private static final Map<String, Breaker> BREAKERS = new ConcurrentHashMap<>();
    private static volatile Clock clock = Clock.systemUTC();

    private CircuitBreakers() {}

    /**
     * {@link HttpClient#send} behind the breaker for the request's host. Also forwards the current
     * request ID ({@link RequestIds}) when the caller did not set one.
     */
    public static <T> HttpResponse<T> send(HttpClient client, HttpRequest original, HttpResponse.BodyHandler<T> body)
            throws IOException, InterruptedException {
        HttpRequest request = original.headers().firstValue(RequestIds.HEADER).isPresent() ? original
                : HttpRequest.newBuilder(original, (name, value) -> true).header(RequestIds.HEADER, RequestIds.current()).build();
        Breaker breaker = BREAKERS.computeIfAbsent(key(request), Breaker::new);
        breaker.before();
        try {
            HttpResponse<T> response = client.send(request, body);
            breaker.succeeded();
            return response;
        } catch (IOException unreachable) {
            breaker.failed();
            throw unreachable;
        }
    }

    /** Current state per dependency, for health and status pages. */
    public static Map<String, State> states() {
        Map<String, State> states = new java.util.TreeMap<>();
        BREAKERS.forEach((key, breaker) -> states.put(key, breaker.state()));
        return states;
    }

    static void reset(Clock testClock) {
        BREAKERS.clear();
        clock = testClock;
    }

    private static String key(HttpRequest request) {
        return request.uri().getHost() + ":" + request.uri().getPort();
    }

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private static final class Breaker {
        private final String name;
        private int failures;
        private Instant openUntil;
        private boolean trialRunning;

        Breaker(String name) {
            this.name = name;
        }

        synchronized void before() throws ConnectException {
            if (openUntil == null) return;
            if (clock.instant().isBefore(openUntil) || trialRunning) {
                throw new ConnectException("Circuit open for " + name);
            }
            trialRunning = true;
        }

        synchronized void succeeded() {
            if (openUntil != null) log.info("Circuit for {} closed again", name);
            failures = 0;
            openUntil = null;
            trialRunning = false;
        }

        synchronized void failed() {
            failures++;
            if (trialRunning || failures >= FAILURE_THRESHOLD) {
                if (openUntil == null || trialRunning) log.warn("Circuit for {} opened after {} unreachable calls", name, failures);
                openUntil = clock.instant().plus(OPEN_FOR);
                trialRunning = false;
            }
        }

        synchronized State state() {
            if (openUntil == null) return State.CLOSED;
            return clock.instant().isBefore(openUntil) ? State.OPEN : State.HALF_OPEN;
        }
    }
}
