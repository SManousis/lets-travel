package com.travelplan.travel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.neo4j.driver.exceptions.Neo4jException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Private read endpoints for search-service, which keeps an Elasticsearch copy of bookable travels.
 * travel-service stays the source of truth: search-service asks here for the current state of one
 * changed travel, or for every bookable travel when it rebuilds its index. Never routed by the gateway;
 * the shared token filter requires the internal service token.
 */
@RestController
final class TravelSearchFeedController {
    private static final int MAX_PAGE_SIZE = 500;
    private final TravelStore store;
    private final ManagerIdentityClient managers;

    TravelSearchFeedController(TravelStore store, ManagerIdentityClient managers) {
        this.store = store;
        this.managers = managers;
    }

    /** The searchable snapshot of one travel in any status; search-service drops it unless {@code bookable}. */
    @GetMapping("/internal/search/travels/{travelId}")
    SearchDocument travel(@PathVariable UUID travelId) {
        return document(store.travel(travelId), destinationNames(), new HashMap<>());
    }

    /** Every bookable travel, oldest first, for a full index rebuild. */
    @GetMapping("/internal/search/travels")
    TravelController.Page<SearchDocument> bookable(@RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "200") int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) throw new IllegalArgumentException();
        List<TravelDomain.Travel> all = store.bookableTravels();
        int from = (int) Math.min((long) page * size, all.size());
        Map<UUID, TravelDomain.Destination> places = destinationNames();
        Map<UUID, String> managerNames = new HashMap<>();
        List<SearchDocument> items = all.subList(from, Math.min(from + size, all.size())).stream()
                .map(travel -> document(travel, places, managerNames)).toList();
        return new TravelController.Page<>(items, page, size, all.size());
    }

    private Map<UUID, TravelDomain.Destination> destinationNames() {
        Map<UUID, TravelDomain.Destination> byId = new HashMap<>();
        for (TravelDomain.Destination destination : store.destinations()) byId.put(destination.id(), destination);
        return byId;
    }

    private SearchDocument document(TravelDomain.Travel travel, Map<UUID, TravelDomain.Destination> places,
                                    Map<UUID, String> managerNames) {
        List<Place> destinations = travel.stops().stream()
                .map(stop -> places.get(stop.destinationId()))
                .filter(java.util.Objects::nonNull)
                .map(destination -> new Place(destination.name(), destination.country())).toList();
        return new SearchDocument(travel.id(), travel.managerId(),
                managerNames.computeIfAbsent(travel.managerId(), managerName()),
                travel.name(), travel.description(), travel.status(), store.bookable(travel),
                travel.startDate(), travel.endDate(), travel.durationDays(), travel.timeZone(), travel.price(),
                travel.currency(), travel.capacity(), store.remainingCapacity(travel), travel.version(), destinations,
                travel.activities().stream().map(a -> new ActivityText(a.name(), a.description(), a.date())).toList(),
                travel.accommodations().stream().map(h -> new StayText(h.name(), h.address())).toList(),
                travel.transports().stream().map(t -> new TransportText(t.mode(), t.departure(), t.arrival())).toList());
    }

    /** The manager's display name, or null when user-service cannot give one; search still works without it. */
    private Function<UUID, String> managerName() {
        return managerId -> {
            try {
                return managers.requireActiveManager(managerId).displayName();
            } catch (RuntimeException ex) {
                return null;
            }
        };
    }

    @ExceptionHandler(TravelStore.NotFound.class)
    ResponseEntity<TravelController.Error> missing() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new TravelController.Error("NOT_FOUND", "Travel not found"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<TravelController.Error> invalid() {
        return ResponseEntity.badRequest().body(new TravelController.Error("INVALID_REQUEST", "Request validation failed"));
    }

    @ExceptionHandler(Neo4jException.class)
    ResponseEntity<TravelController.Error> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new TravelController.Error("TRAVEL_STORE_UNAVAILABLE", "Travel storage unavailable"));
    }

    record SearchDocument(UUID travelId, UUID managerId, String managerName, String title, String description,
                          TravelDomain.Status status, boolean bookable, LocalDate startDate, LocalDate endDate,
                          long durationDays, String timeZone, BigDecimal price, String currency, int capacity,
                          int remainingCapacity, long version, List<Place> destinations,
                          List<ActivityText> activities, List<StayText> accommodations,
                          List<TransportText> transports) {}

    record Place(String name, String country) {}
    record ActivityText(String name, String description, LocalDate date) {}
    record StayText(String name, String address) {}
    record TransportText(String mode, String departure, String arrival) {}
}
