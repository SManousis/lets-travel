package com.travelplan.travel;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

class Neo4jTravelRepositoryTest {
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

    @Test void completeItinerarySurvivesNewStoreInstancesAndCleansUpOwnedNodes() {
        TravelStore first = store();
        var athens = first.createDestination("Athens", "GR");
        var rome = first.createDestination("Rome", "IT");
        assertThrows(TravelStore.Conflict.class, () -> first.createDestination(" ATHENS ", "gr"));
        var firstStop = new TravelDomain.Stop(UUID.randomUUID(), athens.id(), 0,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2));
        var secondStop = new TravelDomain.Stop(UUID.randomUUID(), rome.id(), 1,
                LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 4));
        var activity = new TravelDomain.Activity(UUID.randomUUID(), "Museum", "Morning visit", LocalDate.of(2026, 10, 2));
        var stay = new TravelDomain.Accommodation(UUID.randomUUID(), "Hotel", "Main Street", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3));
        var ride = new TravelDomain.Transport(UUID.randomUUID(), "TRAIN", "Athens", "Rome", LocalDate.of(2026, 10, 3));
        var created = first.create(UUID.randomUUID(), "Autumn trip", "An autumn itinerary",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 4), 8, new BigDecimal("1250.50"), "EUR",
                List.of(firstStop, secondStop), List.of(activity), List.of(stay), List.of(ride));
        UUID traveler = UUID.randomUUID();
        first.addParticipant(created.id(), traveler);
        first.addParticipant(created.id(), traveler);

        TravelStore second = store();
        assertThat(second.destinations()).hasSize(2);
        assertThat(second.destination(athens.id()).createdAt()).isEqualTo(athens.createdAt());
        assertThat(second.updateDestination(rome.id(), "Roma", "IT").createdAt()).isEqualTo(rome.createdAt());
        var reloaded = second.travel(created.id());
        assertThat(reloaded.stops()).containsExactly(firstStop, secondStop);
        assertThat(reloaded.activities()).containsExactly(activity);
        assertThat(reloaded.accommodations()).containsExactly(stay);
        assertThat(reloaded.transports()).containsExactly(ride);
        assertThat(reloaded.travelerIds()).containsExactly(traveler);
        assertThat(second.travels()).extracting(TravelDomain.Travel::id).contains(created.id());
        assertThrows(TravelStore.Conflict.class, () -> second.deleteDestination(athens.id()));

        var updated = second.update(created.id(), "Updated trip", created.description(),
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 4), created.capacity(), created.price(),
                created.currency(), List.of(secondStop, firstStop), List.of(), List.of(stay), List.of());
        assertThat(updated.stops()).containsExactly(firstStop, secondStop);
        assertThat(store().travel(created.id()).name()).isEqualTo("Updated trip");
        assertThat(updated.travelerIds()).containsExactly(traveler);
        assertThat(store().travel(created.id()).activities()).isEmpty();

        second.removeParticipant(created.id(), traveler);
        second.removeParticipant(created.id(), traveler);
        assertThat(store().travel(created.id()).travelerIds()).isEmpty();
        second.delete(created.id());
        assertThrows(TravelStore.NotFound.class, () -> store().travel(created.id()));
        assertThat(store().destinations()).hasSize(2);
        second.deleteDestination(athens.id());
        assertThat(store().destinations()).hasSize(1);
        try (var session = driver.session()) {
            long owned = session.run("MATCH (n) WHERE n:DestinationStop OR n:Activity OR n:Accommodation OR n:Transport OR n:Participation RETURN count(n) AS count")
                    .single().get("count").asLong();
            assertThat(owned).isZero();
        }
    }

    @Test void updateReturnsParticipantsCommittedBeforeItsRootLock() {
        TravelStore store = store();
        var destination = store.createDestination("Lisbon", "PT");
        var stop = new TravelDomain.Stop(UUID.randomUUID(), destination.id(), 0, null, null);
        var created = store.create(UUID.randomUUID(), "Original", "Description", LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 2), 4, new BigDecimal("40.00"), "EUR",
                List.of(stop), List.of(), List.of(), List.of());
        var staleSnapshot = store.travel(created.id());
        UUID traveler = UUID.randomUUID();
        store.addParticipant(created.id(), traveler);

        var replacement = new TravelDomain.Travel(created.id(), created.managerId(), "Updated", created.description(),
                created.startDate(), created.endDate(), created.durationDays(), created.capacity(), created.price(),
                created.currency(), created.status(), created.version() + 1, created.stops(), created.activities(),
                created.accommodations(), created.transports(), staleSnapshot.travelerIds(), created.createdAt(), Instant.now());
        var response = new Neo4jTravelRepository(driver).update(replacement);

        assertThat(response.travelerIds()).containsExactly(traveler);
        assertThat(store.travel(created.id()).travelerIds()).containsExactly(traveler);
    }

    @Test void failedAggregateWriteRollsBackItsTravelNode() {
        UUID travelId = UUID.randomUUID();
        var missingStop = new TravelDomain.Stop(UUID.randomUUID(), UUID.randomUUID(), 0, null, null);
        var travel = new TravelDomain.Travel(travelId, UUID.randomUUID(), "Invalid trip", "Description",
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2), 2, 4, new BigDecimal("10.00"), "EUR",
                TravelDomain.Status.DRAFT, 1, List.of(missingStop), List.of(), List.of(), List.of(), Set.of(),
                Instant.now(), Instant.now());
        assertThrows(TravelStore.Conflict.class, () -> new Neo4jTravelRepository(driver).save(travel));
        try (var session = driver.session()) {
            long roots = session.run("MATCH (t:Travel {id:$id}) RETURN count(t) AS count",
                    java.util.Map.of("id", travelId.toString())).single().get("count").asLong();
            assertThat(roots).isZero();
        }
    }

    @Test void topLevelOnlyLegacyTravelIsNotReturnedAsACompleteItinerary() {
        TravelStore store = store();
        var destination = store.createDestination("Oslo", "NO");
        var stop = new TravelDomain.Stop(UUID.randomUUID(), destination.id(), 0, null, null);
        var complete = store.create(UUID.randomUUID(), "Complete", "Description", LocalDate.of(2026, 12, 1),
                LocalDate.of(2026, 12, 2), 4, new BigDecimal("10.00"), "EUR",
                List.of(stop), List.of(), List.of(), List.of());
        UUID id = UUID.randomUUID();
        try (var session = driver.session()) {
            session.run("CREATE (t:Travel {id:$id, name:'Legacy', startDate:'2026-12-01', "
                            + "endDate:'2026-12-02', durationDays:2, createdAt:$createdAt})",
                    java.util.Map.of("id", id.toString(), "createdAt", java.time.Instant.now().toString())).consume();
        }
        TravelStore.Conflict error = assertThrows(TravelStore.Conflict.class, () -> store.travel(id));
        assertThat(error.code).isEqualTo("INCOMPLETE_LEGACY_TRAVEL");
        assertThat(assertThrows(TravelStore.Conflict.class, store::travels).code)
                .isEqualTo("INCOMPLETE_LEGACY_TRAVEL");
        try (var session = driver.session()) {
            session.run("MATCH (t:Travel {id:$id}) REMOVE t:Travel SET t:LegacyTravelNeedsReview",
                    java.util.Map.of("id", id.toString())).consume();
        }
        assertThat(store.travels()).extracting(TravelDomain.Travel::id).containsExactly(complete.id());
    }

    @Test void roundTripsManagerLifecycleCapacityAndDecimalPrice() {
        TravelStore store = store();
        UUID managerId = UUID.randomUUID();
        var destination = store.createDestination("Naxos", "GR");
        var stop = new TravelDomain.Stop(UUID.randomUUID(), destination.id(), 0,
                LocalDate.of(2027, 6, 1), LocalDate.of(2027, 6, 4));

        var draft = store.create(managerId, "Island week", "Four days in Naxos", LocalDate.of(2027, 6, 1),
                LocalDate.of(2027, 6, 4), 12, new BigDecimal("1099.90"), "eur",
                List.of(stop), List.of(), List.of(), List.of());
        var published = store.publish(draft.id());
        var reloaded = store().travel(draft.id());

        assertThat(reloaded.managerId()).isEqualTo(managerId);
        assertThat(reloaded.status()).isEqualTo(TravelDomain.Status.PUBLISHED);
        assertThat(reloaded.capacity()).isEqualTo(12);
        assertThat(reloaded.price()).isEqualByComparingTo("1099.90");
        assertThat(reloaded.currency()).isEqualTo("EUR");
        assertThat(reloaded.version()).isEqualTo(published.version());
        assertThat(new Neo4jTravelRepository(driver).list(managerId, true)).extracting(TravelDomain.Travel::id)
                .containsExactly(draft.id());
    }

    @Test void quarantinesOwnerlessLegacyTravels() {
        UUID legacyId = UUID.randomUUID();
        try (var session = driver.session()) {
            session.run("CREATE (:Travel {id:$id, name:'Legacy'})", java.util.Map.of("id", legacyId.toString())).consume();
        }

        new Neo4jTravelRepository(driver);
        new Neo4jTravelRepository(driver);

        try (var session = driver.session()) {
            var labels = session.run("MATCH (n {id:$id}) RETURN labels(n) AS labels",
                    java.util.Map.of("id", legacyId.toString())).single().get("labels").asList(value -> value.asString());
            assertThat(labels).containsExactly("LegacyTravelNeedsReview");
        }
        assertThrows(TravelStore.NotFound.class, () -> new Neo4jTravelRepository(driver).travel(legacyId));
    }

    @Test void writesOneOrderedEventPerEffectiveMutation() {
        TravelStore store = store();
        var destination = store.createDestination("Corfu", "GR");
        var stop = new TravelDomain.Stop(UUID.randomUUID(), destination.id(), 0, null, null);
        var created = store.create(UUID.randomUUID(), "Corfu", "Coastal trip", LocalDate.of(2027, 7, 1),
                LocalDate.of(2027, 7, 3), 5, new BigDecimal("300.00"), "EUR",
                List.of(stop), List.of(), List.of(), List.of());
        var updated = store.update(created.id(), "Corfu updated", created.description(), created.startDate(),
                created.endDate(), created.capacity(), created.price(), created.currency(), created.stops(),
                created.activities(), created.accommodations(), created.transports());
        var published = store.publish(created.id());
        store.unpublish(created.id());
        store.delete(created.id());

        try (var session = driver.session()) {
            var events = session.run("MATCH (e:TravelEvent {aggregateId:$id}) RETURN e ORDER BY e.aggregateVersion",
                    java.util.Map.of("id", created.id().toString())).list(r -> r.get("e").asNode());
            assertThat(events).extracting(e -> e.get("eventType").asString()).containsExactly(
                    "TRAVEL_CREATED", "TRAVEL_UPDATED", "TRAVEL_PUBLISHED", "TRAVEL_UNPUBLISHED", "TRAVEL_DELETED");
            assertThat(events).extracting(e -> e.get("aggregateVersion").asLong()).containsExactly(1L, 2L, 3L, 4L, 5L);
            assertThat(events).allSatisfy(e -> {
                assertThat(e.get("eventId").asString()).isNotBlank();
                assertThat(e.get("occurredAt").asString()).isNotBlank();
                assertThat(e.get("payload").asString()).contains("\"managerId\"");
            });
        }
        assertThat(updated.version()).isEqualTo(2);
        assertThat(published.version()).isEqualTo(3);
    }

    @Test void doesNotEmitForIdempotentPublication() {
        TravelStore store = store();
        var destination = store.createDestination("Crete", "GR");
        var stop = new TravelDomain.Stop(UUID.randomUUID(), destination.id(), 0, null, null);
        var created = store.create(UUID.randomUUID(), "Crete", "A complete description", LocalDate.of(2027, 8, 1),
                LocalDate.of(2027, 8, 2), 2, BigDecimal.ZERO, "EUR", List.of(stop), List.of(), List.of(), List.of());
        store.publish(created.id());
        store.publish(created.id());

        try (var session = driver.session()) {
            long published = session.run("MATCH (e:TravelEvent {aggregateId:$id, eventType:'TRAVEL_PUBLISHED'}) RETURN count(e) AS n",
                    java.util.Map.of("id", created.id().toString())).single().get("n").asLong();
            assertThat(published).isOne();
        }
    }

    @Test void concurrentUpdatePreservesParticipantsAndCapacityInvariant() {
        TravelStore store = store();
        var destination = store.createDestination("Paros", "GR");
        var stop = new TravelDomain.Stop(UUID.randomUUID(), destination.id(), 0, null, null);
        var created = store.create(UUID.randomUUID(), "Paros", "Description", LocalDate.of(2027, 9, 1),
                LocalDate.of(2027, 9, 2), 2, new BigDecimal("100"), "EUR", List.of(stop), List.of(), List.of(), List.of());
        var stale = store.travel(created.id());
        UUID travelerId = UUID.randomUUID();
        store.addParticipant(created.id(), travelerId);
        var unsafe = new TravelDomain.Travel(stale.id(), stale.managerId(), stale.name(), stale.description(),
                stale.startDate(), stale.endDate(), stale.durationDays(), 0, stale.price(), stale.currency(),
                stale.status(), stale.version() + 1, stale.stops(), stale.activities(), stale.accommodations(),
                stale.transports(), stale.travelerIds(), stale.createdAt(), Instant.now());

        var error = assertThrows(TravelStore.Conflict.class, () -> new Neo4jTravelRepository(driver).update(unsafe));

        assertThat(error.code).isEqualTo("CAPACITY_BELOW_PARTICIPANTS");
        assertThat(store.travel(created.id()).travelerIds()).containsExactly(travelerId);
        assertThat(store.travel(created.id()).capacity()).isEqualTo(2);
    }

    private static TravelStore store() {
        @SuppressWarnings("unchecked") ObjectProvider<Driver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(driver);
        return new TravelStore(provider);
    }
}
