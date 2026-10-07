package com.travelplan.travel;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

import static com.travelplan.travel.TravelDomain.Accommodation;
import static com.travelplan.travel.TravelDomain.Activity;
import static com.travelplan.travel.TravelDomain.Stop;
import static com.travelplan.travel.TravelDomain.Transport;
import static com.travelplan.travel.TravelDomain.Travel;

final class TravelPolicy {
    private final Clock clock;

    TravelPolicy(Clock clock) {
        this.clock = clock;
    }

    void validateDraft(Travel travel) {
        if (travel.managerId() == null) throw new TravelStore.Conflict("MANAGER_REQUIRED");
        if (travel.name() == null || travel.name().isBlank()) throw new TravelStore.Conflict("INVALID_NAME");
        if (travel.startDate() == null || travel.endDate() == null || travel.endDate().isBefore(travel.startDate()))
            throw new TravelStore.Conflict("INVALID_DATES");
        if (travel.capacity() <= 0) throw new TravelStore.Conflict("INVALID_CAPACITY");
        if (travel.price() == null || travel.price().signum() < 0) throw new TravelStore.Conflict("INVALID_PRICE");
        if (travel.currency() == null || !travel.currency().matches("[A-Z]{3}"))
            throw new TravelStore.Conflict("INVALID_CURRENCY");
        if (!validZone(travel.timeZone())) throw new TravelStore.Conflict("INVALID_TIME_ZONE");
        if (travel.stops() == null || travel.stops().isEmpty())
            throw new TravelStore.Conflict("DESTINATION_REQUIRED");
        if (travel.activities() == null || travel.accommodations() == null || travel.transports() == null)
            throw new TravelStore.Conflict("ITINERARY_REQUIRED");
        if (travel.travelerIds() == null) throw new TravelStore.Conflict("INVALID_PARTICIPANTS");
        if (travel.travelerIds().size() > travel.capacity())
            throw new TravelStore.Conflict("CAPACITY_BELOW_PARTICIPANTS");

        validateItinerary(travel);
    }

    void validateForPublication(Travel travel) {
        validateDraft(travel);
        if (travel.status() == TravelDomain.Status.CANCELLED)
            throw new TravelStore.Conflict("TRAVEL_NOT_EDITABLE");
        if (!travel.startDate().isAfter(today(travel))
                || travel.description() == null || travel.description().isBlank())
            throw new TravelStore.Conflict("PUBLICATION_NOT_READY");
    }

    boolean bookable(Travel travel) {
        return travel.status() == TravelDomain.Status.PUBLISHED
                && travel.startDate().isAfter(today(travel))
                && remainingCapacity(travel) > 0;
    }

    /**
     * Why a traveler cannot take a place, or null when they can. A traveler who already holds a
     * place gets null again, so a retried reservation is a no-op instead of a second seat.
     */
    String reservationProblem(Travel travel, java.util.UUID travelerId) {
        if (travel.travelerIds().contains(travelerId)) return null;
        if (travel.status() != TravelDomain.Status.PUBLISHED || !travel.startDate().isAfter(today(travel)))
            return "TRAVEL_NOT_BOOKABLE";
        if (remainingCapacity(travel) == 0) return "TRAVEL_FULL";
        return null;
    }

    /** Today in the travel's own calendar. */
    LocalDate today(Travel travel) {
        return LocalDate.now(clock.withZone(validZone(travel.timeZone()) ? ZoneId.of(travel.timeZone()) : ZoneId.of("UTC")));
    }

    static boolean validZone(String zone) {
        try {
            ZoneId.of(zone);
            return true;
        } catch (DateTimeException | NullPointerException ex) {
            return false;
        }
    }

    int remainingCapacity(Travel travel) {
        return Math.max(0, travel.capacity() - travel.travelerIds().size());
    }

    private static void validateItinerary(Travel travel) {
        Set<Integer> positions = new HashSet<>();
        for (Stop stop : travel.stops()) {
            if (stop.destinationId() == null || stop.position() < 0 || !positions.add(stop.position()))
                throw new TravelStore.Conflict("INVALID_STOP_ORDER");
            if (!inRange(stop.arrivalDate(), travel) || !inRange(stop.departureDate(), travel)
                    || stop.arrivalDate() != null && stop.departureDate() != null
                    && stop.departureDate().isBefore(stop.arrivalDate()))
                throw new TravelStore.Conflict("STOP_OUTSIDE_TRAVEL");
        }
        for (int position = 0; position < travel.stops().size(); position++)
            if (!positions.contains(position)) throw new TravelStore.Conflict("INVALID_STOP_ORDER");
        for (Activity activity : travel.activities())
            if (!inRange(activity.date(), travel)) throw new TravelStore.Conflict("ACTIVITY_OUTSIDE_TRAVEL");
        for (Accommodation stay : travel.accommodations())
            if (!inRange(stay.checkIn(), travel) || !inRange(stay.checkOut(), travel)
                    || stay.checkIn() != null && stay.checkOut() != null && stay.checkOut().isBefore(stay.checkIn()))
                throw new TravelStore.Conflict("ACCOMMODATION_OUTSIDE_TRAVEL");
        for (Transport transport : travel.transports())
            if (!inRange(transport.date(), travel)) throw new TravelStore.Conflict("TRANSPORT_OUTSIDE_TRAVEL");
    }

    private static boolean inRange(LocalDate value, Travel travel) {
        return value == null || !value.isBefore(travel.startDate()) && !value.isAfter(travel.endDate());
    }
}
