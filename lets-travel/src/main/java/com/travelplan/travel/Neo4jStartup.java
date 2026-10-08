package com.travelplan.travel;

import java.time.Duration;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.neo4j.driver.exceptions.SessionExpiredException;
import org.neo4j.driver.exceptions.TransientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Startup work against Neo4j (constraints, indexes) that waits for a slow or still-starting database
 * instead of failing the whole service. Crashing would cost a full JVM restart per attempt; on a busy
 * host that can take longer than the deployment is willing to wait (docs/operations.md).
 */
public final class Neo4jStartup {
    private static final Logger log = LoggerFactory.getLogger(Neo4jStartup.class);
    static final int ATTEMPTS = 12;

    private Neo4jStartup() {}

    public static void run(String what, Runnable work) {
        run(what, work, Duration.ofSeconds(2), Duration.ofSeconds(30));
    }

    static void run(String what, Runnable work, Duration firstDelay, Duration maxDelay) {
        Duration delay = firstDelay;
        for (int attempt = 1; ; attempt++) {
            try {
                work.run();
                if (attempt > 1) log.info("{} succeeded on attempt {}", what, attempt);
                return;
            } catch (RuntimeException failure) {
                if (!temporary(failure) || attempt >= ATTEMPTS) throw failure;
                log.warn("{} failed (attempt {} of {}), retrying in {}: {}", what, attempt, ATTEMPTS, delay, failure.toString());
                sleep(delay);
                delay = delay.multipliedBy(2).compareTo(maxDelay) > 0 ? maxDelay : delay.multipliedBy(2);
            }
        }
    }

    /** Unreachable, busy or restarting database; also the driver's "no connection within the timeout". */
    static boolean temporary(RuntimeException failure) {
        if (failure instanceof ServiceUnavailableException || failure instanceof SessionExpiredException
                || failure instanceof TransientException) return true;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.util.concurrent.TimeoutException) return true;
        }
        return false;
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Neo4j", interrupted);
        }
    }
}
