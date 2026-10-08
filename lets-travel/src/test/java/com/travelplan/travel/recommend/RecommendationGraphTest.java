package com.travelplan.travel.recommend;

import com.travelplan.travel.TravelDomain;
import com.travelplan.travel.TravelDomain.AccommodationCategory;
import com.travelplan.travel.TravelDomain.ActivityCategory;
import com.travelplan.travel.TravelStore;
import com.travelplan.travel.recommend.RecommendationGraph.Drift;
import com.travelplan.travel.recommend.RecommendationGraph.HistoryRow;
import com.travelplan.travel.recommend.RecommendationGraph.Outcome;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecommendationGraphTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);
    private static Neo4j server;
    private static Driver driver;

    private TravelStore store;
    private RecommendationGraph graph;
    private final UUID ada = UUID.randomUUID();
    private final UUID ben = UUID.randomUUID();
    private final UUID manager = UUID.randomUUID();

    @BeforeAll static void startGraph() {
        server = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build();
        driver = GraphDatabase.driver(server.boltURI());
    }

    @AfterAll static void stopGraph() {
        driver.close();
        server.close();
    }

    @BeforeEach void freshGraph() {
        try (var session = driver.session()) {
            session.run("MATCH (n) DETACH DELETE n").consume();
        }
        @SuppressWarnings("unchecked") ObjectProvider<Driver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(driver);
        store = new TravelStore(provider);
        graph = new RecommendationGraph(driver);
    }

    @Test void confirmedAndCompletedSubscriptionsBecomeHistoryWithFeatures() {
        UUID rome = travel("Rome culture", "Rome", ActivityCategory.CULTURE, "Train", AccommodationCategory.HOTEL, 30, true);
        UUID subscription = UUID.randomUUID();

        assertThat(graph.applySubscription(UUID.randomUUID(), subscription, ada, rome, "PENDING_PAYMENT", 1)).isEqualTo(Outcome.APPLIED);
        assertThat(graph.history(ada)).isEmpty();
        graph.applySubscription(UUID.randomUUID(), subscription, ada, rome, "CONFIRMED", 2);
        graph.applyFeedback(UUID.randomUUID(), UUID.randomUUID(), ada, rome, 5);

        var history = graph.history(ada);
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().rating()).isEqualTo(5);
        TravelFeatures features = history.getFirst().travel();
        assertThat(features.destinations()).containsExactly("ROME");
        assertThat(features.activityCategories()).containsExactly("CULTURE");
        assertThat(features.transportModes()).containsExactly("TRAIN");
        assertThat(features.accommodationCategories()).containsExactly("HOTEL");
        assertThat(features.averageRating()).isEqualTo(5.0);
    }

    @Test void repeatedEventsChangeNothingAndOlderVersionsNeverOverwriteNewerOnes() {
        UUID rome = travel("Rome culture", "Rome", ActivityCategory.CULTURE, "TRAIN", AccommodationCategory.HOTEL, 30, true);
        UUID subscription = UUID.randomUUID();
        UUID confirmed = UUID.randomUUID();

        assertThat(graph.applySubscription(confirmed, subscription, ada, rome, "CONFIRMED", 2)).isEqualTo(Outcome.APPLIED);
        assertThat(graph.applySubscription(confirmed, subscription, ada, rome, "CONFIRMED", 2)).isEqualTo(Outcome.DUPLICATE);
        assertThat(graph.applySubscription(UUID.randomUUID(), subscription, ada, rome, "CANCELLED_BY_TRAVELER", 3)).isEqualTo(Outcome.APPLIED);
        assertThat(graph.history(ada)).isEmpty();

        // A late, older "confirmed" event must not bring the cancelled subscription back.
        assertThat(graph.applySubscription(UUID.randomUUID(), subscription, ada, rome, "CONFIRMED", 2)).isEqualTo(Outcome.STALE);
        assertThat(graph.history(ada)).isEmpty();
    }

    @Test void aRatingThatArrivesFirstIsKeptForTheLaterParticipation() {
        UUID rome = travel("Rome culture", "Rome", ActivityCategory.CULTURE, "TRAIN", AccommodationCategory.HOTEL, 30, true);
        graph.applyFeedback(UUID.randomUUID(), UUID.randomUUID(), ada, rome, 2);
        graph.applySubscription(UUID.randomUUID(), UUID.randomUUID(), ada, rome, "COMPLETED", 4);
        assertThat(graph.history(ada)).singleElement().extracting(RecommendationEngine.History::rating).isEqualTo(2);
    }

    @Test void deletingAUserRemovesTheirHistoryButNotOthers() {
        UUID rome = travel("Rome culture", "Rome", ActivityCategory.CULTURE, "TRAIN", AccommodationCategory.HOTEL, 30, true);
        graph.applySubscription(UUID.randomUUID(), UUID.randomUUID(), ada, rome, "COMPLETED", 3);
        graph.applySubscription(UUID.randomUUID(), UUID.randomUUID(), ben, rome, "COMPLETED", 3);
        graph.applyFeedback(UUID.randomUUID(), UUID.randomUUID(), ada, rome, 1);

        graph.forgetUsers(Set.of(ada));

        assertThat(graph.history(ada)).isEmpty();
        assertThat(graph.history(ben)).hasSize(1);
        assertThat(graph.counts().ratings()).isZero();
    }

    @Test void reconciliationRepairsDriftAndThenFindsNothing() {
        UUID rome = travel("Rome culture", "Rome", ActivityCategory.CULTURE, "TRAIN", AccommodationCategory.HOTEL, 30, true);
        UUID crete = travel("Crete beach", "Crete", ActivityCategory.BEACH, "PLANE", AccommodationCategory.RESORT, 40, true);
        UUID stale = UUID.randomUUID();
        UUID missed = UUID.randomUUID();
        graph.applySubscription(UUID.randomUUID(), stale, ada, crete, "CONFIRMED", 2); // engagement later cancelled it
        graph.applyFeedback(UUID.randomUUID(), UUID.randomUUID(), ada, crete, 4);       // ... and has no such rating

        Drift drift = graph.reconcile(List.of(new HistoryRow(missed, ada, rome, "COMPLETED", 5, 3)));

        assertThat(drift).isEqualTo(new Drift(1, 1, 2));
        assertThat(graph.history(ada)).singleElement().satisfies(entry -> {
            assertThat(entry.travel().travelId()).isEqualTo(rome);
            assertThat(entry.rating()).isEqualTo(3);
        });
        assertThat(graph.reconcile(List.of(new HistoryRow(missed, ada, rome, "COMPLETED", 5, 3))).total()).isZero();
    }

    @Test void onlyBookableTravelsTheCallerHasNotTakenAreCandidates() {
        UUID open = travel("Open", "Rome", ActivityCategory.CULTURE, "TRAIN", AccommodationCategory.HOTEL, 30, true);
        UUID draft = travel("Draft", "Rome", ActivityCategory.CULTURE, "TRAIN", AccommodationCategory.HOTEL, 30, false);
        UUID past = travel("Started", "Rome", ActivityCategory.CULTURE, "TRAIN", AccommodationCategory.HOTEL, -2, true);
        UUID full = travel("Full", "Rome", ActivityCategory.CULTURE, "TRAIN", AccommodationCategory.HOTEL, 30, true, 1);
        UUID booked = travel("Booked", "Rome", ActivityCategory.CULTURE, "TRAIN", AccommodationCategory.HOTEL, 30, true);
        UUID taken = travel("Taken", "Rome", ActivityCategory.CULTURE, "TRAIN", AccommodationCategory.HOTEL, 30, true);
        store.addParticipant(full, ben);
        store.addParticipant(booked, ada);
        graph.applySubscription(UUID.randomUUID(), UUID.randomUUID(), ada, taken, "CONFIRMED", 2);
        UUID own;
        try (var session = driver.session()) {
            own = travel("Own travel", "Rome", ActivityCategory.CULTURE, "TRAIN", AccommodationCategory.HOTEL, 30, true);
            session.run("MATCH (t:Travel {id:$id}) SET t.managerId = $ada", Map.of("id", own.toString(), "ada", ada.toString())).consume();
        }

        assertThat(graph.candidates(ada, TODAY)).extracting(TravelFeatures::travelId).containsExactly(open);
        assertThat(graph.candidates(ben, TODAY)).extracting(TravelFeatures::travelId)
                .containsExactlyInAnyOrder(open, booked, taken, own).doesNotContain(draft, past, full);
    }

    @Test void participantCountIsThePopularitySignal() {
        UUID popular = travel("Popular", "Rome", ActivityCategory.CULTURE, "TRAIN", AccommodationCategory.HOTEL, 30, true);
        store.addParticipant(popular, UUID.randomUUID());
        store.addParticipant(popular, UUID.randomUUID());
        assertThat(graph.candidates(ada, TODAY)).singleElement().extracting(TravelFeatures::participants).isEqualTo(2);
    }

    private UUID travel(String name, String destination, ActivityCategory activity, String transport,
                        AccommodationCategory stay, int startsInDays, boolean published) {
        return travel(name, destination, activity, transport, stay, startsInDays, published, 10);
    }

    private UUID travel(String name, String destination, ActivityCategory activity, String transport,
                        AccommodationCategory stay, int startsInDays, boolean published, int capacity) {
        LocalDate start = TODAY.plusDays(startsInDays);
        LocalDate end = start.plusDays(4);
        UUID place = store.destinations().stream().filter(d -> d.name().equals(destination)).map(TravelDomain.Destination::id)
                .findFirst().orElseGet(() -> store.createDestination(destination, "XX").id());
        var created = store.create(manager, name, name + " itinerary", start, end, capacity, new BigDecimal("700.00"), "EUR",
                List.of(new TravelDomain.Stop(UUID.randomUUID(), place, 0, start, end)),
                List.of(new TravelDomain.Activity(UUID.randomUUID(), "Visit", null, start, activity)),
                List.of(new TravelDomain.Accommodation(UUID.randomUUID(), "Stay", "Main Street", start, end, stay)),
                List.of(new TravelDomain.Transport(UUID.randomUUID(), transport, "Home", destination, start)));
        if (published) {
            try (var session = driver.session()) {
                session.run("MATCH (t:Travel {id:$id}) SET t.status = 'PUBLISHED'", Map.of("id", created.id().toString())).consume();
            }
        }
        return created.id();
    }
}
