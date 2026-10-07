package com.travelplan.travel;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.neo4j.driver.exceptions.Neo4jException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Place reservations for engagement-service subscriptions. travel-service owns capacity, so a place
 * is taken and given back here, atomically against the travel's participant count. These /internal
 * endpoints are never routed by the gateway; the shared token filter requires the service token.
 */
@RestController
final class TravelReservationController {
    private final TravelStore store;

    TravelReservationController(TravelStore store) {
        this.store = store;
    }

    @GetMapping("/internal/travels/{travelId}")
    TravelSummary summary(@PathVariable UUID travelId) {
        return summary(store.travel(travelId));
    }

    @PostMapping("/internal/travels/{travelId}/reservations")
    TravelSummary reserve(@PathVariable UUID travelId, @Valid @RequestBody Reservation body) {
        return summary(store.reserveSeat(travelId, body.travelerId()));
    }

    @DeleteMapping("/internal/travels/{travelId}/reservations/{travelerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void release(@PathVariable UUID travelId, @PathVariable UUID travelerId) {
        store.releaseSeat(travelId, travelerId);
    }

    private TravelSummary summary(TravelDomain.Travel travel) {
        return new TravelSummary(travel.id(), travel.managerId(), travel.name(), travel.status(), travel.startDate(),
                travel.endDate(), travel.timeZone(), travel.price(), travel.currency(), travel.capacity(),
                store.remainingCapacity(travel), store.bookable(travel));
    }

    @ExceptionHandler(TravelStore.NotFound.class)
    ResponseEntity<TravelController.Error> missing() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new TravelController.Error("NOT_FOUND", "Travel not found"));
    }

    @ExceptionHandler(TravelStore.Conflict.class)
    ResponseEntity<TravelController.Error> conflict(TravelStore.Conflict failure) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new TravelController.Error(failure.code, "Place cannot be reserved"));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class})
    ResponseEntity<TravelController.Error> invalid() {
        return ResponseEntity.badRequest().body(new TravelController.Error("INVALID_REQUEST", "Request validation failed"));
    }

    @ExceptionHandler(Neo4jException.class)
    ResponseEntity<TravelController.Error> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new TravelController.Error("TRAVEL_STORE_UNAVAILABLE", "Travel storage unavailable"));
    }

    record Reservation(@NotNull UUID travelerId) {}

    record TravelSummary(UUID travelId, UUID managerId, String title, TravelDomain.Status status, LocalDate startDate,
                         LocalDate endDate, String timeZone, BigDecimal price, String currency, int capacity,
                         int remainingCapacity, boolean bookable) {}
}
