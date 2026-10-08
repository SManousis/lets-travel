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
    /** What kind of experience an activity is; one of the fields recommendations match on. */
    public enum ActivityCategory { CULTURE, NATURE, ADVENTURE, BEACH, FOOD, NIGHTLIFE, WELLNESS, SPORT }

    /** What kind of place a stay is; one of the fields recommendations match on. */
    public enum AccommodationCategory { HOTEL, HOSTEL, APARTMENT, RESORT, CAMPING }

    /** {@code category} is optional; travels created before categories existed have none. */
    public record Activity(UUID id, String name, String description, LocalDate date, ActivityCategory category) {
        public Activity(UUID id, String name, String description, LocalDate date) {
            this(id, name, description, date, null);
        }
    }

    /** {@code category} is optional; travels created before categories existed have none. */
    public record Accommodation(UUID id, String name, String address, LocalDate checkIn, LocalDate checkOut,
                                AccommodationCategory category) {
        public Accommodation(UUID id, String name, String address, LocalDate checkIn, LocalDate checkOut) {
            this(id, name, address, checkIn, checkOut, null);
        }
    }
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
            Instant updatedAt,
            String timeZone) {

        /** Calendar zone of the travel's dates; the cancellation cutoff and "has started" use it. */
        public static final String DEFAULT_TIME_ZONE = "UTC";

        public Travel {
            stops = stops == null ? null : List.copyOf(stops);
            activities = activities == null ? null : List.copyOf(activities);
            accommodations = accommodations == null ? null : List.copyOf(accommodations);
            transports = transports == null ? null : List.copyOf(transports);
            travelerIds = travelerIds == null ? null : Set.copyOf(travelerIds);
            currency = currency == null ? null : currency.toUpperCase(Locale.ROOT);
            timeZone = timeZone == null || timeZone.isBlank() ? DEFAULT_TIME_ZONE : timeZone;
        }

        /** Travels created before time zones existed use UTC. */
        public Travel(UUID id, UUID managerId, String name, String description, LocalDate startDate,
                      LocalDate endDate, long durationDays, int capacity, BigDecimal price, String currency,
                      Status status, long version, List<Stop> stops, List<Activity> activities,
                      List<Accommodation> accommodations, List<Transport> transports, Set<UUID> travelerIds,
                      Instant createdAt, Instant updatedAt) {
            this(id, managerId, name, description, startDate, endDate, durationDays, capacity, price, currency,
                    status, version, stops, activities, accommodations, transports, travelerIds, createdAt, updatedAt,
                    DEFAULT_TIME_ZONE);
        }

        public Travel withTravelers(Set<UUID> ids) {
            return new Travel(id, managerId, name, description, startDate, endDate, durationDays, capacity, price,
                    currency, status, version, stops, activities, accommodations, transports, ids, createdAt,
                    updatedAt, timeZone);
        }

        /** Compatibility constructor for inherited records until their Phase 2 migration runs. */
        public Travel(UUID id, String name, LocalDate startDate, LocalDate endDate, long durationDays,
                      List<Stop> stops, List<Activity> activities, List<Accommodation> accommodations,
                      List<Transport> transports, Set<UUID> travelerIds, Instant createdAt) {
            this(id, null, name, "", startDate, endDate, durationDays, Integer.MAX_VALUE, BigDecimal.ZERO,
                    "EUR", Status.DRAFT, 1, stops, activities, accommodations, transports, travelerIds,
                    createdAt, createdAt, DEFAULT_TIME_ZONE);
        }
    }
}
