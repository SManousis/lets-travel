package com.travelplan.travel.recommend;

import com.travelplan.travel.TravelDomain;
import com.travelplan.travel.TravelDomain.AccommodationCategory;
import com.travelplan.travel.TravelDomain.ActivityCategory;
import com.travelplan.travel.TravelStore;
import com.travelplan.travel.recommend.RecommendationEngine.Recommendations;
import com.travelplan.travel.recommend.RecommendationEngine.Strategy;
import com.travelplan.travel.recommend.RecommendationService.Event;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The audit scenario end to end: engagement events build two travelers' histories in Neo4j, and
 * their recommendations differ and are explained by their own trips and ratings.
 */
class RecommendationServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);
    private static Neo4j server;
    private static Driver driver;

    private TravelStore store;
    private RecommendationService service;
    private final UUID manager = UUID.randomUUID();
    private final UUID culture = UUID.randomUUID();
    private final UUID beach = UUID.randomUUID();
    private UUID florence;
    private UUID rhodes;

    @BeforeAll static void startGraph() {
        server = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build();
        driver = GraphDatabase.driver(server.boltURI());
    }

    @AfterAll static void stopGraph() {
        driver.close();
        server.close();
    }

    @BeforeEach void twoTravelersWithDifferentPasts() {
        try (var session = driver.session()) {
            session.run("MATCH (n) DETACH DELETE n").consume();
        }
        @SuppressWarnings("unchecked") ObjectProvider<Driver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(driver);
        store = new TravelStore(provider);
        service = new RecommendationService(new RecommendationGraph(driver),
                Clock.fixed(Instant.parse("2026-10-08T09:00:00Z"), ZoneOffset.UTC));

        UUID rome = travel("Rome museums", "Rome", ActivityCategory.CULTURE, "TRAIN", AccommodationCategory.HOTEL, -200);
        UUID crete = travel("Crete beaches", "Crete", ActivityCategory.BEACH, "PLANE", AccommodationCategory.RESORT, -150);
        florence = travel("Florence art", "Florence", ActivityCategory.CULTURE, "TRAIN", AccommodationCategory.HOTEL, 40);
        rhodes = travel("Rhodes sun", "Rhodes", ActivityCategory.BEACH, "PLANE", AccommodationCategory.RESORT, 50);
        travel("Alpine hike", "Alps", ActivityCategory.ADVENTURE, "BUS", AccommodationCategory.CAMPING, 60);

        completedAndRated(culture, rome, 5);
        completedAndRated(beach, crete, 5);
    }

    @Test void twoTravelersGetDistinctExplainedRecommendations() {
        Recommendations forCulture = service.recommend(culture, 5);
        Recommendations forBeach = service.recommend(beach, 5);

        assertThat(forCulture.strategy()).isEqualTo(Strategy.PERSONALIZED);
        assertThat(forCulture.items().getFirst().travel().travelId()).isEqualTo(florence);
        assertThat(forCulture.items().getFirst().reasons()).extracting(RecommendationEngine.Reason::value)
                .containsExactlyInAnyOrder("CULTURE", "TRAIN", "HOTEL");
        assertThat(forBeach.items().getFirst().travel().travelId()).isEqualTo(rhodes);
        assertThat(forBeach.items().getFirst().reasons()).extracting(RecommendationEngine.Reason::value)
                .containsExactlyInAnyOrder("BEACH", "PLANE", "RESORT");
        assertThat(service.recommend(culture, 5)).as("reproducible").isEqualTo(forCulture);
    }

    @Test void newTravelersGetPopularTravels() {
        UUID newcomer = UUID.randomUUID();
        store.addParticipant(rhodes, UUID.randomUUID());
        Recommendations result = service.recommend(newcomer, 3);
        assertThat(result.strategy()).isEqualTo(Strategy.POPULAR);
        assertThat(result.items().getFirst().travel().travelId()).isEqualTo(rhodes);
    }

    @Test void aCancelledBookingNoLongerShapesRecommendations() {
        UUID subscription = UUID.randomUUID();
        UUID traveler = UUID.randomUUID();
        UUID lisbon = travel("Lisbon trams", "Lisbon", ActivityCategory.CULTURE, "TRAIN", AccommodationCategory.HOTEL, -100);
        service.apply(List.of(subscriptionEvent(subscription, traveler, lisbon, "CONFIRMED", 2)));
        assertThat(service.recommend(traveler, 5).strategy()).isEqualTo(Strategy.PERSONALIZED);

        service.apply(List.of(subscriptionEvent(subscription, traveler, lisbon, "CANCELLED_BY_TRAVELER", 3)));
        assertThat(service.recommend(traveler, 5).strategy()).isEqualTo(Strategy.POPULAR);
    }

    @Test void eventBatchesReportDuplicatesStaleAndUnknownEvents() {
        UUID subscription = UUID.randomUUID();
        Event confirmed = subscriptionEvent(subscription, culture, florence, "CONFIRMED", 5);
        var result = service.apply(List.of(confirmed, confirmed, subscriptionEvent(subscription, culture, florence, "CONFIRMED", 4),
                new Event(UUID.randomUUID(), "ReportFiled", UUID.randomUUID(), 1, Map.of())));
        assertThat(result).isEqualTo(new RecommendationService.EventResult(1, 1, 1, 1));
    }

    @Test void deletedTravelersAreForgotten() {
        service.forgetUsers(Set.of(culture));
        assertThat(service.recommend(culture, 5).strategy()).isEqualTo(Strategy.POPULAR);
        assertThat(service.recommend(beach, 5).strategy()).isEqualTo(Strategy.PERSONALIZED);
    }

    @Test void reconciliationRecordsItsResultAndFailures() {
        var repaired = service.reconcile(List::of);
        assertThat(repaired.drift().total()).as("both histories removed").isEqualTo(4);
        assertThat(service.status().lastReconcile()).isEqualTo(repaired);

        var failed = service.reconcile(() -> { throw new IllegalStateException("engagement-service unreachable"); });
        assertThat(failed.error()).isEqualTo("IllegalStateException");
        assertThat(service.status().graphAvailable()).isTrue();
    }

    @Test void withoutNeo4jEverythingReportsUnavailable() {
        RecommendationService offline = new RecommendationService(null, Clock.systemUTC());
        assertThatThrownBy(() -> offline.recommend(culture, 5)).isInstanceOf(RecommendationService.Unavailable.class);
        assertThatThrownBy(() -> offline.apply(List.of())).isInstanceOf(RecommendationService.Unavailable.class);
        assertThat(offline.status().graphAvailable()).isFalse();
    }

    private void completedAndRated(UUID traveler, UUID travel, int rating) {
        UUID subscription = UUID.randomUUID();
        service.apply(List.of(subscriptionEvent(subscription, traveler, travel, "COMPLETED", 3),
                new Event(UUID.randomUUID(), "FeedbackSubmitted", UUID.randomUUID(), 1,
                        Map.of("travelerId", traveler.toString(), "travelId", travel.toString(), "rating", rating))));
    }

    private static Event subscriptionEvent(UUID subscription, UUID traveler, UUID travel, String status, long version) {
        return new Event(UUID.randomUUID(), "Subscription" + (status.startsWith("CANCELLED") ? "Cancelled" : "Confirmed"),
                subscription, version, Map.of("travelerId", traveler.toString(), "travelId", travel.toString(), "status", status));
    }

    private UUID travel(String name, String destination, ActivityCategory activity, String transport,
                        AccommodationCategory stay, int startsInDays) {
        LocalDate start = TODAY.plusDays(startsInDays);
        LocalDate end = start.plusDays(4);
        UUID place = store.createDestination(destination, "XX").id();
        var created = store.create(manager, name, name, start, end, 10, new BigDecimal("650.00"), "EUR",
                List.of(new TravelDomain.Stop(UUID.randomUUID(), place, 0, start, end)),
                List.of(new TravelDomain.Activity(UUID.randomUUID(), "Visit", null, start, activity)),
                List.of(new TravelDomain.Accommodation(UUID.randomUUID(), "Stay", "Main Street", start, end, stay)),
                List.of(new TravelDomain.Transport(UUID.randomUUID(), transport, "Home", destination, start)));
        try (var session = driver.session()) {
            session.run("MATCH (t:Travel {id:$id}) SET t.status = 'PUBLISHED'", Map.of("id", created.id().toString())).consume();
        }
        return created.id();
    }
}
