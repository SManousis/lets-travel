package com.travelplan.search.index;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One travel as stored in the Elasticsearch index. travel-service sends the same shape from
 * /internal/search/travels (without {@code indexedAt}); the index mapping in
 * resources/elasticsearch/travel-index-v1.json lists exactly these fields.
 */
public record TravelDocument(
        UUID travelId,
        UUID managerId,
        String managerName,
        String title,
        String description,
        String status,
        Boolean bookable,
        LocalDate startDate,
        LocalDate endDate,
        Integer durationDays,
        String timeZone,
        BigDecimal price,
        String currency,
        Integer capacity,
        Integer remainingCapacity,
        Long version,
        List<Place> destinations,
        List<ActivityText> activities,
        List<StayText> accommodations,
        List<TransportText> transports,
        Instant indexedAt) {

    public TravelDocument {
        destinations = destinations == null ? List.of() : List.copyOf(destinations);
        activities = activities == null ? List.of() : List.copyOf(activities);
        accommodations = accommodations == null ? List.of() : List.copyOf(accommodations);
        transports = transports == null ? List.of() : List.copyOf(transports);
    }

    /** Only published travels that can still be booked are searchable. */
    public boolean searchable() {
        return Boolean.TRUE.equals(bookable) && "PUBLISHED".equals(status);
    }

    public TravelDocument indexedAt(Instant when) {
        return new TravelDocument(travelId, managerId, managerName, title, description, status, bookable, startDate,
                endDate, durationDays, timeZone, price, currency, capacity, remainingCapacity, version, destinations,
                activities, accommodations, transports, when);
    }

    public record Place(String name, String country) {}
    public record ActivityText(String name, String description, LocalDate date) {}
    public record StayText(String name, String address) {}
    public record TransportText(String mode, String departure, String arrival) {}
}
