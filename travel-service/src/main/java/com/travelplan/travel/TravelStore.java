package com.travelplan.travel;

import com.travelplan.travel.TravelDomain.*;
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
    private final Map<UUID, Destination> destinations = new ConcurrentHashMap<>();
    private final Map<UUID, Travel> travels = new ConcurrentHashMap<>();
    private final Set<UUID> removedTravelerIds = ConcurrentHashMap.newKeySet();

    public TravelStore() { graph = null; }

    @Autowired
    public TravelStore(ObjectProvider<Driver> provider) {
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
        if (graph != null) { graph.delete(id); return; }
        travel(id);
        travels.remove(id);
    }

    private static Travel withTravelers(Travel old, Set<UUID> ids) {
        return new Travel(old.id(), old.name(), old.startDate(), old.endDate(), old.durationDays(), old.stops(),
                old.activities(), old.accommodations(), old.transports(), Set.copyOf(ids), old.createdAt());
    }

    private static List<Stop> orderedStops(List<Stop> stops) {
        return stops.stream().sorted(Comparator.comparingInt(Stop::position)).toList();
    }

    private static long duration(LocalDate start, LocalDate end) { return start.datesUntil(end.plusDays(1)).count(); }

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
