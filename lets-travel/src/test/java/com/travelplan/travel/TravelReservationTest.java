package com.travelplan.travel;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Place reservations for subscriptions: capacity, bookability, idempotency and the last-place race. */
class TravelReservationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC);
    private static Neo4j server;
    private static Driver driver;

    @BeforeAll static void startGraph() {
        server = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build();
        driver = GraphDatabase.driver(server.boltURI());
    }

    @AfterAll static void stopGraph() {
        driver.close();
        server.close();
    }

    @BeforeEach void clearGraph() {
        try (var session = driver.session()) {
            session.run("MATCH (n) DETACH DELETE n").consume();
        }
    }

    @Test void inMemoryReservationChecksBookabilityCapacityAndIsIdempotent() {
        TravelStore store = new TravelStore(CLOCK);
        var travel = publishedTravel(store, 1, LocalDate.of(2026, 12, 1));
        UUID first = UUID.randomUUID();

        store.reserveSeat(travel.id(), first);
        var again = store.reserveSeat(travel.id(), first);
        assertThat(again.travelerIds()).containsExactly(first);
        assertConflict("TRAVEL_FULL", () -> store.reserveSeat(travel.id(), UUID.randomUUID()));

        store.releaseSeat(travel.id(), first);
        store.releaseSeat(travel.id(), first);
        assertThat(store.travel(travel.id()).travelerIds()).isEmpty();
        store.releaseSeat(UUID.randomUUID(), first);

        store.unpublish(travel.id());
        assertConflict("TRAVEL_NOT_BOOKABLE", () -> store.reserveSeat(travel.id(), first));
        assertThrows(TravelStore.NotFound.class, () -> store.reserveSeat(UUID.randomUUID(), first));
    }

    @Test void startedTravelIsNotBookableInItsOwnCalendar() {
        // 2026-10-07T00:00Z is already 2026-10-07 in Tokyo but still 2026-10-06 in New York.
        TravelStore store = new TravelStore(CLOCK);
        var destination = store.createDestination("Kyoto", "JP");
        var stop = new TravelDomain.Stop(UUID.randomUUID(), destination.id(), 0, null, null);
        var tokyo = store.create(UUID.randomUUID(), "Kyoto", "Description", LocalDate.of(2026, 10, 8),
                LocalDate.of(2026, 10, 9), 2, BigDecimal.TEN, "JPY", List.of(stop), List.of(), List.of(), List.of(),
                "Asia/Tokyo");
        store.publish(tokyo.id());
        assertThat(store.today(store.travel(tokyo.id()))).isEqualTo(LocalDate.of(2026, 10, 7));
        assertThat(store.today(new TravelDomain.Travel(tokyo.id(), tokyo.managerId(), "x", "x", tokyo.startDate(),
                tokyo.endDate(), 2, 2, BigDecimal.ONE, "USD", TravelDomain.Status.PUBLISHED, 1, List.of(), List.of(),
                List.of(), List.of(), java.util.Set.of(), Instant.EPOCH, Instant.EPOCH, "America/New_York")))
                .isEqualTo(LocalDate.of(2026, 10, 6));
        store.reserveSeat(tokyo.id(), UUID.randomUUID());
    }

    @Test void concurrentReservationsNeverOversellTheLastPlaceInNeo4j() throws Exception {
        TravelStore store = graphStore();
        var travel = publishedTravel(store, 3, LocalDate.of(2027, 6, 1));
        int callers = 12;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        store.reserveSeat(travel.id(), UUID.randomUUID());
                        return "OK";
                    } catch (TravelStore.Conflict conflict) {
                        return conflict.code;
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) outcomes.add(result.get());
            assertThat(outcomes).filteredOn("OK"::equals).hasSize(3);
            assertThat(outcomes).filteredOn("TRAVEL_FULL"::equals).hasSize(callers - 3);
        } finally {
            pool.shutdownNow();
        }
        assertThat(store.travel(travel.id()).travelerIds()).hasSize(3);
        assertThat(store.remainingCapacity(store.travel(travel.id()))).isZero();
    }

    @Test void neo4jReservationIsIdempotentAndReleasable() {
        TravelStore store = graphStore();
        var travel = publishedTravel(store, 2, LocalDate.of(2027, 6, 1));
        UUID traveler = UUID.randomUUID();

        store.reserveSeat(travel.id(), traveler);
        store.reserveSeat(travel.id(), traveler);
        assertThat(store.travel(travel.id()).travelerIds()).containsExactly(traveler);
        assertConflict("TRAVEL_HAS_SUBSCRIBERS", () -> store.update(travel.id(), travel.name(), travel.description(),
                travel.startDate().plusDays(1), travel.endDate().plusDays(1), 2, travel.price(), travel.currency(),
                travel.stops(), List.of(), List.of(), List.of()));

        store.releaseSeat(travel.id(), traveler);
        store.releaseSeat(travel.id(), traveler);
        assertThat(store.travel(travel.id()).travelerIds()).isEmpty();
        store.releaseSeat(UUID.randomUUID(), traveler);
    }

    private static TravelDomain.Travel publishedTravel(TravelStore store, int capacity, LocalDate start) {
        var destination = store.createDestination("Place " + UUID.randomUUID(), "GR");
        var stop = new TravelDomain.Stop(UUID.randomUUID(), destination.id(), 0, null, null);
        var created = store.create(UUID.randomUUID(), "Trip", "Description", start, start.plusDays(2), capacity,
                new BigDecimal("100.00"), "EUR", List.of(stop), List.of(), List.of(), List.of());
        return store.publish(created.id());
    }

    private static TravelStore graphStore() {
        @SuppressWarnings("unchecked") ObjectProvider<Driver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(driver);
        return new TravelStore(provider);
    }

    private static void assertConflict(String code, Runnable action) {
        var error = assertThrows(TravelStore.Conflict.class, action::run);
        assertThat(error.code).isEqualTo(code);
    }
}
