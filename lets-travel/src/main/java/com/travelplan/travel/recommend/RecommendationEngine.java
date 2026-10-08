package com.travelplan.travel.recommend;

import com.travelplan.travel.recommend.TravelFeatures.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ranks eligible travels for one traveler from their own history. Pure and deterministic: the same
 * history and candidates always give the same list, in the same order.
 *
 * <ol>
 *   <li>Each past travel (confirmed or completed) weighs {@code 1 + 0.5 x (rating - 3)}: 5 stars give 2.0,
 *       3 stars or no rating 1.0, 1 star 0. A low rating therefore adds little or nothing.</li>
 *   <li>For each field value (destination "Rome", activity "CULTURE", ...) the preference is the sum of the
 *       weights of past travels that have it, divided by the largest preference in the same field (0..1).</li>
 *   <li>A candidate scores {@code 0.35 x destination + 0.25 x activity + 0.20 x transport + 0.20 x
 *       accommodation}, each term being the strongest preference among the candidate's values.</li>
 *   <li>Ties break on average rating (higher first), then start date (sooner first), then travel ID.</li>
 *   <li>For variety, at most {@link #PER_DESTINATION} travels to the same first destination are listed while
 *       other matching travels remain.</li>
 * </ol>
 * Without usable history (no past travels, or only 1-star ones) the result is the POPULAR fallback:
 * most places taken, then best rated, then soonest.
 */
public final class RecommendationEngine {
    static final int PER_DESTINATION = 2;
    private static final int MAX_REASONS = 3;

    public Recommendations recommend(List<History> history, List<TravelFeatures> candidates, int limit) {
        Map<Field, Map<String, Double>> preferences = preferences(history);
        if (preferences.values().stream().allMatch(Map::isEmpty)) return popular(candidates, limit);

        List<Recommendation> scored = new ArrayList<>();
        for (TravelFeatures candidate : candidates) {
            double score = 0;
            List<Reason> reasons = new ArrayList<>();
            for (Field field : Field.values()) {
                String best = null;
                double strongest = 0;
                for (String value : sorted(candidate.values(field))) {
                    double preference = preferences.get(field).getOrDefault(value, 0.0);
                    if (preference > strongest) { strongest = preference; best = value; }
                }
                if (best != null) {
                    score += field.weight * strongest;
                    reasons.add(new Reason(field, best, field.weight * strongest));
                }
            }
            if (score > 0) {
                reasons.sort(Comparator.comparingDouble(Reason::contribution).reversed()
                        .thenComparing(reason -> reason.field().ordinal()));
                scored.add(new Recommendation(candidate, round(score), reasons.stream().limit(MAX_REASONS).toList()));
            }
        }
        scored.sort(Comparator.comparingDouble(Recommendation::score).reversed().thenComparing(TIE_BREAK));
        return new Recommendations(Strategy.PERSONALIZED, diversify(scored, limit));
    }

    private static Map<Field, Map<String, Double>> preferences(List<History> history) {
        Map<Field, Map<String, Double>> sums = new EnumMap<>(Field.class);
        for (Field field : Field.values()) sums.put(field, new HashMap<>());
        for (History past : history) {
            double weight = past.weight();
            if (weight <= 0) continue;
            for (Field field : Field.values()) {
                for (String value : past.travel().values(field)) sums.get(field).merge(value, weight, Double::sum);
            }
        }
        for (Map<String, Double> field : sums.values()) {
            double max = field.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
            if (max > 0) field.replaceAll((value, sum) -> sum / max);
        }
        return sums;
    }

    /** Keeps the ranking but lets at most PER_DESTINATION travels share a first destination while others wait. */
    private static List<Recommendation> diversify(List<Recommendation> ranked, int limit) {
        List<Recommendation> chosen = new ArrayList<>();
        List<Recommendation> held = new ArrayList<>();
        Map<String, Integer> perDestination = new LinkedHashMap<>();
        for (Recommendation item : ranked) {
            if (chosen.size() == limit) break;
            String destination = firstDestination(item.travel());
            if (perDestination.getOrDefault(destination, 0) < PER_DESTINATION) {
                chosen.add(item);
                perDestination.merge(destination, 1, Integer::sum);
            } else {
                held.add(item);
            }
        }
        for (Recommendation item : held) {
            if (chosen.size() == limit) break;
            chosen.add(item);
        }
        return chosen;
    }

    private static Recommendations popular(List<TravelFeatures> candidates, int limit) {
        return new Recommendations(Strategy.POPULAR, candidates.stream()
                .sorted(Comparator.comparingInt(TravelFeatures::participants).reversed()
                        .thenComparing(Comparator.comparing(TravelFeatures::averageRating,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                        .thenComparing(TravelFeatures::startDate)
                        .thenComparing(TravelFeatures::travelId))
                .limit(limit)
                .map(travel -> new Recommendation(travel, 0, List.of()))
                .toList());
    }

    private static final Comparator<Recommendation> TIE_BREAK = Comparator
            .comparing((Recommendation item) -> item.travel().averageRating(), Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(item -> item.travel().startDate())
            .thenComparing(item -> item.travel().travelId());

    private static String firstDestination(TravelFeatures travel) {
        return sorted(travel.destinations()).stream().findFirst().orElse("");
    }

    private static List<String> sorted(java.util.Set<String> values) {
        return values.stream().sorted().toList();
    }

    private static double round(double score) {
        return Math.round(score * 10_000) / 10_000.0;
    }

    /** One past travel of the traveler and their rating of it (null when they gave none). */
    public record History(TravelFeatures travel, Integer rating) {
        double weight() {
            return rating == null ? 1.0 : 1.0 + 0.5 * (rating - 3);
        }
    }

    public enum Strategy { PERSONALIZED, POPULAR }

    /** A field value of the traveler's own history that this travel matches. */
    public record Reason(Field field, String value, double contribution) {}

    public record Recommendation(TravelFeatures travel, double score, List<Reason> reasons) {}

    public record Recommendations(Strategy strategy, List<Recommendation> items) {}
}
