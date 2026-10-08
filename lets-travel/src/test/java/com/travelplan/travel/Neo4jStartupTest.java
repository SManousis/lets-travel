package com.travelplan.travel;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.driver.exceptions.ServiceUnavailableException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Neo4jStartupTest {
    @Test void waitsForADatabaseThatIsStillStarting() {
        AtomicInteger attempts = new AtomicInteger();
        Neo4jStartup.run("schema", () -> {
            if (attempts.incrementAndGet() < 3) throw new ServiceUnavailableException("starting");
        }, Duration.ofMillis(1), Duration.ofMillis(2));
        assertThat(attempts).hasValue(3);
    }

    @Test void treatsAConnectionPoolTimeoutAsTemporary() {
        var poolTimeout = new ClientException("Unable to acquire connection from the pool",
                new java.util.concurrent.TimeoutException("60000ms"));
        assertThat(Neo4jStartup.temporary(poolTimeout)).isTrue();
        assertThat(Neo4jStartup.temporary(new ClientException("Invalid syntax"))).isFalse();
    }

    @Test void givesUpOnPermanentErrorsAtOnceAndOnTemporaryOnesEventually() {
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> Neo4jStartup.run("schema", () -> {
            attempts.incrementAndGet();
            throw new ClientException("Invalid syntax");
        }, Duration.ofMillis(1), Duration.ofMillis(2))).isInstanceOf(ClientException.class);
        assertThat(attempts).hasValue(1);

        attempts.set(0);
        assertThatThrownBy(() -> Neo4jStartup.run("schema", () -> {
            attempts.incrementAndGet();
            throw new ServiceUnavailableException("down");
        }, Duration.ofMillis(1), Duration.ofMillis(2))).isInstanceOf(ServiceUnavailableException.class);
        assertThat(attempts).hasValue(Neo4jStartup.ATTEMPTS);
    }
}
