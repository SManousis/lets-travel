package com.travelplan.travel.recommend;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * The parts of a travel that recommendations look at. Values are normalized (trimmed, upper case
 * for categories and transport modes) so "Train" and "TRAIN" match.
 *
 * @param averageRating average of all travelers' ratings, or null when nobody rated it yet
 * @param participants  places currently taken; the popularity signal for the cold-start fallback
 */
public record TravelFeatures(UUID travelId, String title, LocalDate startDate, LocalDate endDate, BigDecimal price,
                             String currency, Set<String> destinations, Set<String> activityCategories,
                             Set<String> transportModes, Set<String> accommodationCategories, Double averageRating,
                             int participants) {

    public TravelFeatures {
        destinations = Set.copyOf(destinations);
        activityCategories = Set.copyOf(activityCategories);
        transportModes = Set.copyOf(transportModes);
        accommodationCategories = Set.copyOf(accommodationCategories);
    }

    Set<String> values(Field field) {
        return switch (field) {
            case DESTINATION -> destinations;
            case ACTIVITY_CATEGORY -> activityCategories;
            case TRANSPORT_MODE -> transportModes;
            case ACCOMMODATION_CATEGORY -> accommodationCategories;
        };
    }

    /** The four fields recommendations match on, with their weight in the score. */
    public enum Field {
        DESTINATION(0.35), ACTIVITY_CATEGORY(0.25), TRANSPORT_MODE(0.20), ACCOMMODATION_CATEGORY(0.20);

        final double weight;

        Field(double weight) {
            this.weight = weight;
        }
    }
}
