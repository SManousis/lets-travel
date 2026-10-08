package com.travelplan.travel.recommend;

import com.travelplan.travel.recommend.RecommendationEngine.History;
import com.travelplan.travel.recommend.RecommendationEngine.Recommendation;
import com.travelplan.travel.recommend.RecommendationEngine.Recommendations;
import com.travelplan.travel.recommend.RecommendationEngine.Strategy;
import com.travelplan.travel.recommend.TravelFeatures.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationEngineTest {
    private final RecommendationEngine engine = new RecommendationEngine();

    // Past travels: a culture-by-train city break and a beach-by-plane resort holiday.
    private final TravelFeatures romeCulture = travel("Rome culture", "ROME", "CULTURE", "TRAIN", "HOTEL", null, 0, 1);
    private final TravelFeatures creteBeach = travel("Crete beach", "CRETE", "BEACH", "PLANE", "RESORT", null, 0, 1);

    // Upcoming candidates.
    private final TravelFeatures florence = travel("Florence museums", "FLORENCE", "CULTURE", "TRAIN", "HOTEL", 4.5, 3, 10);
    private final TravelFeatures romeAgain = travel("Rome food tour", "ROME", "FOOD", "TRAIN", "APARTMENT", 4.0, 2, 12);
    private final TravelFeatures rhodes = travel("Rhodes sun", "RHODES", "BEACH", "PLANE", "RESORT", 4.8, 9, 14);
    private final TravelFeatures alps = travel("Alpine hiking", "ALPS", "ADVENTURE", "BUS", "CAMPING", 3.9, 20, 20);
    private final List<TravelFeatures> candidates = List.of(florence, romeAgain, rhodes, alps);

    @Test void twoTravelersWithDifferentHistoriesGetDifferentExplainedRankings() {
        Recommendations cultureLover = engine.recommend(List.of(new History(romeCulture, 5)), candidates, 10);
        Recommendations beachLover = engine.recommend(List.of(new History(creteBeach, 5)), candidates, 10);

        assertThat(cultureLover.strategy()).isEqualTo(Strategy.PERSONALIZED);
        assertThat(titles(cultureLover)).startsWith("Florence museums", "Rome food tour");
        assertThat(cultureLover.items().getFirst().reasons()).extracting(RecommendationEngine.Reason::value)
                .contains("CULTURE", "TRAIN", "HOTEL");
        assertThat(titles(beachLover)).first().isEqualTo("Rhodes sun");
        assertThat(beachLover.items().getFirst().reasons()).extracting(RecommendationEngine.Reason::field)
                .contains(Field.ACTIVITY_CATEGORY, Field.TRANSPORT_MODE, Field.ACCOMMODATION_CATEGORY);
        assertThat(titles(cultureLover)).isNotEqualTo(titles(beachLover));
    }

    @Test void onlyTravelsSharingSomethingWithTheHistoryAreRecommended() {
        Recommendations result = engine.recommend(List.of(new History(romeCulture, 5)), candidates, 10);
        assertThat(titles(result)).doesNotContain("Alpine hiking", "Rhodes sun");
        assertThat(result.items()).allSatisfy(item -> assertThat(item.score()).isPositive());
    }

    @Test void destinationCountsMostThenActivity() {
        // Same activity only (0.25) loses to same destination only (0.35).
        TravelFeatures sameActivity = travel("Athens museums", "ATHENS", "CULTURE", "CAR", "HOSTEL", null, 0, 10);
        TravelFeatures sameDestination = travel("Rome nights", "ROME", "NIGHTLIFE", "CAR", "HOSTEL", null, 0, 10);
        Recommendations result = engine.recommend(List.of(new History(romeCulture, null)),
                List.of(sameActivity, sameDestination), 10);
        assertThat(titles(result)).containsExactly("Rome nights", "Athens museums");
        assertThat(result.items().get(0).score()).isEqualTo(0.35);
        assertThat(result.items().get(1).score()).isEqualTo(0.25);
    }

    @Test void aHighRatingOutweighsALowOne() {
        List<History> history = List.of(new History(romeCulture, 5), new History(creteBeach, 2));
        Recommendations result = engine.recommend(history, candidates, 10);
        assertThat(result.items().getFirst().travel()).isEqualTo(florence);
        assertThat(score(result, florence)).isGreaterThan(score(result, rhodes));
    }

    @Test void aOneStarTripAddsNothingAndAloneFallsBackToPopular() {
        Recommendations onlyDisliked = engine.recommend(List.of(new History(creteBeach, 1)), candidates, 10);
        assertThat(onlyDisliked.strategy()).isEqualTo(Strategy.POPULAR);

        Recommendations mixed = engine.recommend(List.of(new History(romeCulture, 4), new History(creteBeach, 1)), candidates, 10);
        assertThat(titles(mixed)).doesNotContain("Rhodes sun");
    }

    @Test void coldStartReturnsPopularThenBestRatedThenSoonest() {
        TravelFeatures tiedPopular = travel("Lisbon trams", "LISBON", "CULTURE", "TRAIN", "HOTEL", 4.9, 20, 25);
        Recommendations result = engine.recommend(List.of(), List.of(florence, alps, rhodes, tiedPopular), 3);
        assertThat(result.strategy()).isEqualTo(Strategy.POPULAR);
        assertThat(titles(result)).containsExactly("Lisbon trams", "Alpine hiking", "Rhodes sun");
        assertThat(result.items()).allSatisfy(item -> assertThat(item.reasons()).isEmpty());
    }

    @Test void tiesBreakOnRatingThenStartDateThenId() {
        TravelFeatures a = travel("A", "ROME", "FOOD", "CAR", "HOSTEL", 4.0, 0, 30);
        TravelFeatures betterRated = travel("B", "ROME", "FOOD", "CAR", "HOSTEL", 4.5, 0, 40);
        TravelFeatures sooner = travel("C", "ROME", "FOOD", "CAR", "HOSTEL", 4.0, 0, 10);
        Recommendations result = engine.recommend(List.of(new History(romeCulture, null)), List.of(a, betterRated, sooner), 10);
        assertThat(titles(result)).containsExactly("B", "C", "A");
    }

    @Test void atMostTwoTravelsPerDestinationWhileOthersMatch() {
        List<TravelFeatures> romeHeavy = List.of(
                travel("Rome 1", "ROME", "CULTURE", "TRAIN", "HOTEL", 5.0, 0, 10),
                travel("Rome 2", "ROME", "CULTURE", "TRAIN", "HOTEL", 4.9, 0, 11),
                travel("Rome 3", "ROME", "CULTURE", "TRAIN", "HOTEL", 4.8, 0, 12),
                travel("Naples food", "NAPLES", "CULTURE", "TRAIN", "APARTMENT", 3.0, 0, 13));
        Recommendations result = engine.recommend(List.of(new History(romeCulture, 5)), romeHeavy, 3);
        assertThat(titles(result)).containsExactly("Rome 1", "Rome 2", "Naples food");

        Recommendations roomForAll = engine.recommend(List.of(new History(romeCulture, 5)), romeHeavy, 10);
        assertThat(titles(roomForAll)).containsExactly("Rome 1", "Rome 2", "Naples food", "Rome 3");
    }

    @Test void theSameInputAlwaysGivesTheSameRanking() {
        List<History> history = List.of(new History(romeCulture, 4), new History(creteBeach, 3));
        List<TravelFeatures> shuffled = new ArrayList<>(candidates);
        Recommendations first = engine.recommend(history, candidates, 10);
        for (int seed = 0; seed < 5; seed++) {
            Collections.shuffle(shuffled, new Random(seed));
            assertThat(engine.recommend(history, shuffled, 10)).isEqualTo(first);
        }
    }

    @Test void respectsTheLimitAndAtMostThreeReasons() {
        Recommendations result = engine.recommend(List.of(new History(romeCulture, 5), new History(creteBeach, 5)), candidates, 2);
        assertThat(result.items()).hasSize(2);
        assertThat(result.items()).allSatisfy(item -> assertThat(item.reasons()).hasSizeLessThanOrEqualTo(3));
    }

    private static List<String> titles(Recommendations result) {
        return result.items().stream().map(item -> item.travel().title()).toList();
    }

    private static double score(Recommendations result, TravelFeatures travel) {
        return result.items().stream().filter(item -> item.travel().equals(travel)).mapToDouble(Recommendation::score)
                .findFirst().orElse(0);
    }

    private static TravelFeatures travel(String title, String destination, String activity, String transport,
                                         String accommodation, Double rating, int participants, int daysAhead) {
        return new TravelFeatures(UUID.nameUUIDFromBytes(title.getBytes()), title, LocalDate.of(2027, 1, 1).plusDays(daysAhead),
                LocalDate.of(2027, 1, 1).plusDays(daysAhead + 5), new BigDecimal("500.00"), "EUR", Set.of(destination),
                Set.of(activity), Set.of(transport), Set.of(accommodation), rating, participants);
    }
}
