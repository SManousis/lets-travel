package com.travelplan.travel;

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
        var created = first.save("Autumn trip", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 4),
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

        var updated = second.update(created.id(), "Updated trip", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 4),
                List.of(secondStop, firstStop), List.of(), List.of(stay), List.of());
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
        var created = store.save("Original", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2),
                List.of(stop), List.of(), List.of(), List.of());
        var staleSnapshot = store.travel(created.id());
        UUID traveler = UUID.randomUUID();
        store.addParticipant(created.id(), traveler);

        var replacement = new TravelDomain.Travel(created.id(), "Updated", created.startDate(), created.endDate(),
                created.durationDays(), created.stops(), created.activities(), created.accommodations(),
                created.transports(), staleSnapshot.travelerIds(), created.createdAt());
        var response = new Neo4jTravelRepository(driver).update(replacement);

        assertThat(response.travelerIds()).containsExactly(traveler);
        assertThat(store.travel(created.id()).travelerIds()).containsExactly(traveler);
    }

    @Test void failedAggregateWriteRollsBackItsTravelNode() {
        UUID travelId = UUID.randomUUID();
        var missingStop = new TravelDomain.Stop(UUID.randomUUID(), UUID.randomUUID(), 0, null, null);
        var travel = new TravelDomain.Travel(travelId, "Invalid trip", LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 2), 2, List.of(missingStop), List.of(), List.of(), List.of(), Set.of(),
                java.time.Instant.now());
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
        var complete = store.save("Complete", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 2),
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

    private static TravelStore store() {
        @SuppressWarnings("unchecked") ObjectProvider<Driver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(driver);
        return new TravelStore(provider);
    }
}
