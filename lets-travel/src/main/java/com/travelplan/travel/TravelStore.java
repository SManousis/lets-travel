package com.travelplan.travel;

import com.travelplan.travel.TravelDomain.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TravelStore {
    private final Neo4jTravelRepository graph;
    private final Clock clock;
    private final TravelPolicy policy;
    private final Map<UUID, Destination> destinations = new ConcurrentHashMap<>();
    private final Map<UUID, Travel> travels = new ConcurrentHashMap<>();
    private final Set<UUID> removedTravelerIds = ConcurrentHashMap.newKeySet();

    public TravelStore() { this(Clock.systemUTC()); }

    TravelStore(Clock clock) {
        this.graph = null;
        this.clock = clock;
        this.policy = new TravelPolicy(clock);
    }

    @Autowired
    public TravelStore(ObjectProvider<Driver> provider) {
        this.clock = Clock.systemUTC();
        this.policy = new TravelPolicy(clock);
        Driver driver = provider.getIfAvailable();
        graph = driver == null ? null : new Neo4jTravelRepository(driver);
    }

    public List<Destination> destinations() {
        return graph != null ? graph.destinations() : destinations.values().stream()
                .sorted(Comparator.comparing(Destination::createdAt).thenComparing(Destination::id)).toList();
    }

    public Destination destination(UUID id) {
        if (graph != null) return graph.destination(id);
        Destination result = destinations.get(id);
        if (result == null) throw new NotFound();
        return result;
    }

    public Destination createDestination(String name, String country) {
        Destination result = new Destination(UUID.randomUUID(), name, country, Instant.now());
        if (graph != null) return graph.createDestination(result);
        if (destinations.values().stream().anyMatch(d -> d.name().equalsIgnoreCase(name)
                && d.country().equalsIgnoreCase(country))) throw new Conflict("DESTINATION_EXISTS");
        destinations.put(result.id(), result);
        return result;
    }

    public Destination updateDestination(UUID id, String name, String country) {
        Destination old = destination(id);
        Destination result = new Destination(id, name, country, old.createdAt());
        if (graph != null) return graph.updateDestination(result);
        if (destinations.values().stream().anyMatch(d -> !d.id().equals(id)
                && d.name().equalsIgnoreCase(name) && d.country().equalsIgnoreCase(country)))
            throw new Conflict("DESTINATION_EXISTS");
        destinations.put(id, result);
        return result;
    }

    public void deleteDestination(UUID id) {
        if (graph != null) { graph.deleteDestination(id); return; }
        destination(id);
        if (travels.values().stream().anyMatch(t -> t.stops().stream().anyMatch(s -> s.destinationId().equals(id))))
            throw new Conflict("DESTINATION_IN_USE");
        destinations.remove(id);
    }

    public List<Travel> travels() {
        return graph != null ? graph.travels() : travels.values().stream()
                .sorted(Comparator.comparing(Travel::createdAt).thenComparing(Travel::id)).toList();
    }

    public List<Travel> travelsForManager(UUID managerId) {
        if (graph != null) return graph.list(managerId, false);
        return travels.values().stream().filter(travel -> managerId.equals(travel.managerId()))
                .sorted(Comparator.comparing(Travel::createdAt).thenComparing(Travel::id)).toList();
    }

    public List<Travel> bookableTravels() {
        List<Travel> candidates = graph != null ? graph.list(null, true) : travels.values().stream().toList();
        return candidates.stream().filter(policy::bookable)
                .sorted(Comparator.comparing(Travel::createdAt).thenComparing(Travel::id)).toList();
    }

    public boolean bookable(Travel travel) { return policy.bookable(travel); }

    public int remainingCapacity(Travel travel) { return policy.remainingCapacity(travel); }

    public Travel travel(UUID id) {
        if (graph != null) return graph.travel(id);
        Travel result = travels.get(id);
        if (result == null) throw new NotFound();
        return result;
    }

    public Travel save(String name, LocalDate start, LocalDate end, List<Stop> stops,
                       List<Activity> activities, List<Accommodation> stays, List<Transport> transports) {
        validate(start, end, stops, activities, stays, transports);
        Travel result = new Travel(UUID.randomUUID(), name, start, end, duration(start, end), orderedStops(stops),
                List.copyOf(activities), List.copyOf(stays), List.copyOf(transports), Set.of(), Instant.now());
        if (graph != null) return graph.save(result);
        travels.put(result.id(), result);
        return result;
    }

    public Travel create(UUID managerId, String name, String description, LocalDate start, LocalDate end,
                         int capacity, BigDecimal price, String currency, List<Stop> stops,
                         List<Activity> activities, List<Accommodation> stays, List<Transport> transports) {
        return create(managerId, name, description, start, end, capacity, price, currency, stops, activities, stays,
                transports, null);
    }

    public Travel create(UUID managerId, String name, String description, LocalDate start, LocalDate end,
                         int capacity, BigDecimal price, String currency, List<Stop> stops,
                         List<Activity> activities, List<Accommodation> stays, List<Transport> transports,
                         String timeZone) {
        Instant now = clock.instant();
        Travel result = new Travel(UUID.randomUUID(), managerId, name, description, start, end,
                safeDuration(start, end), capacity, price, currency, Status.DRAFT, 1, orderedStops(stops),
                copy(activities), copy(stays), copy(transports), Set.of(), now, now, timeZone);
        validateDestinations(result.stops());
        policy.validateDraft(result);
        if (graph != null) return graph.save(result);
        travels.put(result.id(), result);
        return result;
    }

    public synchronized Travel update(UUID id, String name, LocalDate start, LocalDate end, List<Stop> stops,
                         List<Activity> activities, List<Accommodation> stays, List<Transport> transports) {
        Travel old = travel(id);
        validate(start, end, stops, activities, stays, transports);
        Travel result = new Travel(id, name, start, end, duration(start, end), orderedStops(stops),
                List.copyOf(activities), List.copyOf(stays), List.copyOf(transports), old.travelerIds(), old.createdAt());
        if (graph != null) return graph.update(result);
        travels.put(id, result);
        return result;
    }

    public synchronized Travel update(UUID id, String name, String description, LocalDate start, LocalDate end,
                                      int capacity, BigDecimal price, String currency, List<Stop> stops,
                                      List<Activity> activities, List<Accommodation> stays,
                                      List<Transport> transports) {
        return update(id, name, description, start, end, capacity, price, currency, stops, activities, stays,
                transports, null);
    }

    /**
     * Once places are taken, subscriptions hold a snapshot of the dates, time zone and price, so
     * those are fixed (TRAVEL_HAS_SUBSCRIBERS). Name, description, itinerary details and a capacity
     * that still fits every participant may change; the TravelUpdated event tells subscribers.
     * A null time zone keeps the current one.
     */
    public synchronized Travel update(UUID id, String name, String description, LocalDate start, LocalDate end,
                                      int capacity, BigDecimal price, String currency, List<Stop> stops,
                                      List<Activity> activities, List<Accommodation> stays,
                                      List<Transport> transports, String timeZone) {
        Travel old = travel(id);
        String zone = timeZone == null ? old.timeZone() : timeZone;
        if (old.status() == Status.CANCELLED) throw new Conflict("TRAVEL_NOT_EDITABLE");
        if (capacity < old.travelerIds().size()) throw new Conflict("CAPACITY_BELOW_PARTICIPANTS");
        if (!old.travelerIds().isEmpty() && (!Objects.equals(start, old.startDate())
                || !Objects.equals(end, old.endDate()) || price == null || old.price().compareTo(price) != 0
                || !old.currency().equalsIgnoreCase(String.valueOf(currency)) || !old.timeZone().equals(zone)))
            throw new Conflict("TRAVEL_HAS_SUBSCRIBERS");
        Travel result = new Travel(id, old.managerId(), name, description, start, end, safeDuration(start, end),
                capacity, price, currency, old.status(), old.version() + 1, orderedStops(stops), copy(activities),
                copy(stays), copy(transports), old.travelerIds(), old.createdAt(), clock.instant(), zone);
        validateDestinations(result.stops());
        policy.validateDraft(result);
        if (graph != null) return graph.update(result);
        travels.put(id, result);
        return result;
    }

    public synchronized Travel publish(UUID id) {
        Travel old = travel(id);
        if (old.status() == Status.PUBLISHED) return old;
        policy.validateForPublication(old);
        Travel result = withLifecycle(old, Status.PUBLISHED);
        if (graph != null) return graph.update(result);
        travels.put(id, result);
        return result;
    }

    public synchronized Travel unpublish(UUID id) {
        Travel old = travel(id);
        if (old.status() == Status.UNPUBLISHED || old.status() == Status.DRAFT) return old;
        if (old.status() == Status.CANCELLED) throw new Conflict("TRAVEL_NOT_EDITABLE");
        Travel result = withLifecycle(old, Status.UNPUBLISHED);
        if (graph != null) return graph.update(result);
        travels.put(id, result);
        return result;
    }

    public synchronized Travel addParticipant(UUID id, UUID traveler) {
        if (graph != null) return graph.addParticipant(id, traveler);
        if (removedTravelerIds.contains(traveler)) throw new Conflict("TRAVELER_REMOVED");
        Travel old = travel(id);
        Set<UUID> ids = new HashSet<>(old.travelerIds());
        ids.add(traveler);
        Travel result = withTravelers(old, ids);
        travels.put(id, result);
        return result;
    }

    public synchronized void removeParticipant(UUID id, UUID traveler) {
        if (graph != null) { graph.removeParticipant(id, traveler); return; }
        Travel old = travel(id);
        Set<UUID> ids = new HashSet<>(old.travelerIds());
        ids.remove(traveler);
        travels.put(id, withTravelers(old, ids));
    }

    /**
     * Atomically takes one place for a subscription (see Neo4jTravelRepository#reserve). Fails with
     * TRAVEL_NOT_BOOKABLE, TRAVEL_FULL or TRAVELER_REMOVED; repeating it for the same traveler is a no-op.
     */
    public Travel reserveSeat(UUID id, UUID traveler) {
        // Not synchronized on the graph path: Neo4j's lock on the travel node is what serializes callers.
        if (graph != null) return graph.reserve(id, traveler, travel -> policy.reservationProblem(travel, traveler));
        return reserveInMemory(id, traveler);
    }

    private synchronized Travel reserveInMemory(UUID id, UUID traveler) {
        if (removedTravelerIds.contains(traveler)) throw new Conflict("TRAVELER_REMOVED");
        Travel old = travel(id);
        String problem = policy.reservationProblem(old, traveler);
        if (problem != null) throw new Conflict(problem);
        Set<UUID> ids = new HashSet<>(old.travelerIds());
        ids.add(traveler);
        Travel result = withTravelers(old, ids);
        travels.put(id, result);
        return result;
    }

    /** Gives a place back; releasing a place that is not held, or on a deleted travel, does nothing. */
    public void releaseSeat(UUID id, UUID traveler) {
        try {
            removeParticipant(id, traveler);
        } catch (NotFound ignored) {
            // Nothing is held on a travel that no longer exists.
        }
    }

    public LocalDate today(Travel travel) { return policy.today(travel); }

    public synchronized void removeParticipants(Set<UUID> travelerIds) {
        if (travelerIds.isEmpty()) return;
        if (graph != null) { graph.removeParticipants(travelerIds); return; }
        removedTravelerIds.addAll(travelerIds);
        travels.replaceAll((id, old) -> {
            Set<UUID> remaining = new HashSet<>(old.travelerIds());
            remaining.removeAll(travelerIds);
            return withTravelers(old, remaining);
        });
    }

    public void delete(UUID id) {
        Travel current = travel(id);
        if (!current.travelerIds().isEmpty()) throw new Conflict("TRAVEL_IN_USE");
        if (graph != null) { graph.delete(id); return; }
        travels.remove(id);
    }

    private static Travel withTravelers(Travel old, Set<UUID> ids) {
        return old.withTravelers(Set.copyOf(ids));
    }

    private Travel withLifecycle(Travel old, Status status) {
        return new Travel(old.id(), old.managerId(), old.name(), old.description(), old.startDate(), old.endDate(),
                old.durationDays(), old.capacity(), old.price(), old.currency(), status, old.version() + 1, old.stops(),
                old.activities(), old.accommodations(), old.transports(), old.travelerIds(), old.createdAt(), clock.instant(),
                old.timeZone());
    }

    private static List<Stop> orderedStops(List<Stop> stops) {
        return stops.stream().sorted(Comparator.comparingInt(Stop::position)).toList();
    }

    private static long duration(LocalDate start, LocalDate end) { return start.datesUntil(end.plusDays(1)).count(); }

    private static long safeDuration(LocalDate start, LocalDate end) {
        return start == null || end == null || end.isBefore(start) ? 0 : duration(start, end);
    }

    private void validateDestinations(List<Stop> stops) {
        if (stops == null) return;
        for (Stop stop : stops) if (stop.destinationId() != null) destination(stop.destinationId());
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? null : List.copyOf(values);
    }

    private void validate(LocalDate start, LocalDate end, List<Stop> stops, List<Activity> activities,
                          List<Accommodation> stays, List<Transport> transports) {
        if (start == null || end == null || end.isBefore(start)) throw new Conflict("INVALID_DATES");
        if (stops == null || stops.isEmpty()) throw new Conflict("DESTINATION_REQUIRED");
        if (activities == null || stays == null || transports == null) throw new Conflict("ITINERARY_REQUIRED");
        Set<Integer> positions = new HashSet<>();
        for (Stop stop : stops) {
            destination(stop.destinationId());
            if (stop.position() < 0 || !positions.add(stop.position())) throw new Conflict("INVALID_STOP_ORDER");
            if (!inRange(stop.arrivalDate(), start, end) || !inRange(stop.departureDate(), start, end)
                    || stop.arrivalDate() != null && stop.departureDate() != null
                    && stop.departureDate().isBefore(stop.arrivalDate())) throw new Conflict("STOP_OUTSIDE_TRAVEL");
        }
        for (int position = 0; position < stops.size(); position++)
            if (!positions.contains(position)) throw new Conflict("INVALID_STOP_ORDER");
        for (Activity activity : activities)
            if (!inRange(activity.date(), start, end)) throw new Conflict("ACTIVITY_OUTSIDE_TRAVEL");
        for (Accommodation stay : stays)
            if (!inRange(stay.checkIn(), start, end) || !inRange(stay.checkOut(), start, end)
                    || stay.checkIn() != null && stay.checkOut() != null && stay.checkOut().isBefore(stay.checkIn()))
                throw new Conflict("ACCOMMODATION_OUTSIDE_TRAVEL");
        for (Transport transport : transports)
            if (!inRange(transport.date(), start, end)) throw new Conflict("TRANSPORT_OUTSIDE_TRAVEL");
    }

    private static boolean inRange(LocalDate value, LocalDate start, LocalDate end) {
        return value == null || !value.isBefore(start) && !value.isAfter(end);
    }

    public static class NotFound extends RuntimeException {}
    public static class Conflict extends RuntimeException {
        public final String code;
        public Conflict(String code) { this.code = code; }
    }
}
