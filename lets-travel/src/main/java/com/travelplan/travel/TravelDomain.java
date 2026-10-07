package com.travelplan.travel;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class TravelDomain {
    private TravelDomain() {}

    public enum Status { DRAFT, PUBLISHED, UNPUBLISHED, CANCELLED }

    public record Destination(UUID id, String name, String country, Instant createdAt) {}
    public record Stop(UUID id, UUID destinationId, int position, LocalDate arrivalDate, LocalDate departureDate) {}
    public record Activity(UUID id, String name, String description, LocalDate date) {}
    public record Accommodation(UUID id, String name, String address, LocalDate checkIn, LocalDate checkOut) {}
    public record Transport(UUID id, String mode, String departure, String arrival, LocalDate date) {}

    public record Travel(
            UUID id,
            UUID managerId,
            String name,
            String description,
            LocalDate startDate,
            LocalDate endDate,
            long durationDays,
            int capacity,
            BigDecimal price,
            String currency,
            Status status,
            long version,
            List<Stop> stops,
            List<Activity> activities,
            List<Accommodation> accommodations,
            List<Transport> transports,
            Set<UUID> travelerIds,
            Instant createdAt,
            Instant updatedAt) {

        public Travel {
            stops = stops == null ? null : List.copyOf(stops);
            activities = activities == null ? null : List.copyOf(activities);
            accommodations = accommodations == null ? null : List.copyOf(accommodations);
            transports = transports == null ? null : List.copyOf(transports);
            travelerIds = travelerIds == null ? null : Set.copyOf(travelerIds);
            currency = currency == null ? null : currency.toUpperCase(Locale.ROOT);
        }

        /** Compatibility constructor for inherited records until their Phase 2 migration runs. */
        public Travel(UUID id, String name, LocalDate startDate, LocalDate endDate, long durationDays,
                      List<Stop> stops, List<Activity> activities, List<Accommodation> accommodations,
                      List<Transport> transports, Set<UUID> travelerIds, Instant createdAt) {
            this(id, null, name, "", startDate, endDate, durationDays, Integer.MAX_VALUE, BigDecimal.ZERO,
                    "EUR", Status.DRAFT, 1, stops, activities, accommodations, transports, travelerIds,
                    createdAt, createdAt);
        }
    }
}
