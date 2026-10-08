package com.travelplan.travel;

import com.travelplan.common.security.AccessToken;
import com.travelplan.common.security.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.neo4j.driver.exceptions.Neo4jException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TravelController {
    private final TravelStore store;
    private final ManagerIdentityClient managers;
    private final byte[] serviceToken;

    public TravelController(TravelStore store, ManagerIdentityClient managers,
            @Value("${security.internal-service-token:change-me-internal}") String serviceToken) {
        this.store = store;
        this.managers = managers;
        this.serviceToken = serviceToken.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/api/destinations")
    Page<TravelDomain.Destination> destinations(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return page(store.destinations(), page, size);
    }

    @PostMapping("/api/destinations")
    ResponseEntity<TravelDomain.Destination> createDestination(@Valid @RequestBody DestinationWrite body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(store.createDestination(body.name(), body.country()));
    }

    @GetMapping("/api/destinations/{id}")
    TravelDomain.Destination destination(@PathVariable UUID id) { return store.destination(id); }

    @PatchMapping("/api/destinations/{id}")
    TravelDomain.Destination updateDestination(@PathVariable UUID id, @Valid @RequestBody DestinationWrite body) {
        return store.updateDestination(id, body.name(), body.country());
    }

    @DeleteMapping("/api/destinations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteDestination(@PathVariable UUID id) { store.deleteDestination(id); }

    @GetMapping("/api/travels")
    Page<TravelView> travels(@AuthenticationPrincipal AccessToken access,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "20") int size) {
        List<TravelDomain.Travel> visible = switch (access.role()) {
            case ADMIN -> store.travels();
            case TRAVEL_MANAGER -> store.travelsForManager(access.userId());
            case TRAVELER -> store.bookableTravels();
        };
        return page(visible.stream().map(this::view).toList(), page, size);
    }

    @PostMapping("/api/travels")
    ResponseEntity<TravelView> createTravel(@AuthenticationPrincipal AccessToken access,
                                            @Valid @RequestBody TravelWrite body) {
        UUID managerId;
        if (access.role() == Role.ADMIN) {
            if (body.managerId() == null) throw new TravelStore.Conflict("MANAGER_REQUIRED");
            managerId = body.managerId();
        } else {
            managerId = access.userId();
        }
        managers.requireActiveManager(managerId);
        TravelDomain.Travel travel = store.create(managerId, body.name(), body.description(), body.startDate(),
                body.endDate(), body.capacity(), body.price(), body.currency(), stops(body.stops()),
                activities(body.activities()), accommodations(body.accommodations()), transports(body.transports()),
                body.timeZone());
        return ResponseEntity.status(HttpStatus.CREATED).body(view(travel));
    }

    @GetMapping("/api/travels/{id}")
    TravelView travel(@AuthenticationPrincipal AccessToken access, @PathVariable UUID id) {
        return view(authorized(id, access, false));
    }

    @PutMapping("/api/travels/{id}")
    TravelView updateTravel(@AuthenticationPrincipal AccessToken access, @PathVariable UUID id,
                            @Valid @RequestBody TravelWrite body) {
        authorized(id, access, true);
        return view(store.update(id, body.name(), body.description(), body.startDate(), body.endDate(),
                body.capacity(), body.price(), body.currency(), stops(body.stops()), activities(body.activities()),
                accommodations(body.accommodations()), transports(body.transports()), body.timeZone()));
    }

    @DeleteMapping("/api/travels/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteTravel(@AuthenticationPrincipal AccessToken access, @PathVariable UUID id) {
        authorized(id, access, true);
        store.delete(id);
    }

    @PostMapping("/api/travels/{id}/publication")
    TravelView publish(@AuthenticationPrincipal AccessToken access, @PathVariable UUID id) {
        authorized(id, access, true);
        return view(store.publish(id));
    }

    @DeleteMapping("/api/travels/{id}/publication")
    TravelView unpublish(@AuthenticationPrincipal AccessToken access, @PathVariable UUID id) {
        authorized(id, access, true);
        return view(store.unpublish(id));
    }

    @GetMapping("/internal/managers/{managerId}/travel-statistics")
    ResponseEntity<TravelStatistics> statistics(@PathVariable UUID managerId,
            @RequestHeader(value = "X-Internal-Service-Token", required = false) String presented) {
        if (presented == null
                || !MessageDigest.isEqual(serviceToken, presented.getBytes(StandardCharsets.UTF_8)))
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        List<TravelDomain.Travel> owned = store.travelsForManager(managerId);
        long completed = owned.stream().filter(travel -> travel.endDate().isBefore(LocalDate.now())).count();
        return ResponseEntity.ok(new TravelStatistics(managerId, completed, owned.size()));
    }

    private TravelDomain.Travel authorized(UUID id, AccessToken access, boolean mutation) {
        TravelDomain.Travel travel = store.travel(id);
        if (access.role() == Role.ADMIN) return travel;
        if (access.role() == Role.TRAVEL_MANAGER && access.userId().equals(travel.managerId())) return travel;
        if (!mutation && access.role() == Role.TRAVELER && store.bookable(travel)) return travel;
        throw new TravelStore.NotFound();
    }

    private TravelView view(TravelDomain.Travel travel) {
        return new TravelView(travel.id(), travel.managerId(), travel.name(), travel.description(), travel.startDate(),
                travel.endDate(), travel.durationDays(), travel.capacity(), travel.price(), travel.currency(),
                travel.status(), travel.version(), travel.stops(), travel.activities(), travel.accommodations(),
                travel.transports(), travel.travelerIds().size(), store.remainingCapacity(travel), store.bookable(travel),
                travel.timeZone(), travel.createdAt(), travel.updatedAt());
    }

    @ExceptionHandler(TravelStore.NotFound.class)
    ResponseEntity<Error> missing() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Error("NOT_FOUND", "Resource not found"));
    }

    @ExceptionHandler(TravelStore.Conflict.class)
    ResponseEntity<Error> conflict(TravelStore.Conflict failure) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new Error(failure.code, "Request conflicts with current state"));
    }

    @ExceptionHandler(ManagerIdentityClient.DependencyUnavailable.class)
    ResponseEntity<Error> dependencyUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new Error("DEPENDENCY_UNAVAILABLE", "Required service unavailable"));
    }

    @ExceptionHandler(Neo4jException.class)
    ResponseEntity<Error> graphUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new Error("TRAVEL_STORE_UNAVAILABLE", "Travel storage unavailable"));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class})
    ResponseEntity<Error> invalid() {
        return ResponseEntity.badRequest().body(new Error("INVALID_REQUEST", "Request validation failed"));
    }

    private static <T> Page<T> page(List<T> all, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException();
        int from = Math.min(page * size, all.size());
        return new Page<>(all.subList(from, Math.min(from + size, all.size())), page, size, all.size());
    }

    private static List<TravelDomain.Stop> stops(List<StopWrite> values) {
        return values.stream().map(stop -> new TravelDomain.Stop(UUID.randomUUID(), stop.destinationId(),
                stop.position(), stop.arrivalDate(), stop.departureDate())).toList();
    }

    private static List<TravelDomain.Activity> activities(List<ActivityWrite> values) {
        return values.stream().map(activity -> new TravelDomain.Activity(UUID.randomUUID(), activity.name(),
                activity.description(), activity.date(), activity.category())).toList();
    }

    private static List<TravelDomain.Accommodation> accommodations(List<AccommodationWrite> values) {
        return values.stream().map(stay -> new TravelDomain.Accommodation(UUID.randomUUID(), stay.name(),
                stay.address(), stay.checkIn(), stay.checkOut(), stay.category())).toList();
    }

    private static List<TravelDomain.Transport> transports(List<TransportWrite> values) {
        return values.stream().map(transport -> new TravelDomain.Transport(UUID.randomUUID(), transport.mode(),
                transport.departure(), transport.arrival(), transport.date())).toList();
    }

    record Page<T>(List<T> items, int page, int size, long total) {}
    record Error(String code, String message) {}
    record TravelStatistics(UUID managerId, long completedTravelCount, long totalTravelCount) {}
    record TravelView(UUID id, UUID managerId, String name, String description, LocalDate startDate,
                      LocalDate endDate, long durationDays, int capacity, BigDecimal price, String currency,
                      TravelDomain.Status status, long version, List<TravelDomain.Stop> stops,
                      List<TravelDomain.Activity> activities, List<TravelDomain.Accommodation> accommodations,
                      List<TravelDomain.Transport> transports, int participantCount, int remainingCapacity,
                      boolean bookable, String timeZone, Instant createdAt, Instant updatedAt) {}
    record DestinationWrite(@NotBlank String name, @NotBlank String country) {}
    record TravelWrite(UUID managerId, @NotBlank String name, @NotBlank @Size(max = 4000) String description,
                       @NotNull LocalDate startDate, @NotNull LocalDate endDate, @Min(1) int capacity,
                       @NotNull @DecimalMin("0.00") BigDecimal price,
                       @NotBlank @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z]{3}") String currency,
                       @NotEmpty List<@Valid StopWrite> stops, @NotNull List<@Valid ActivityWrite> activities,
                       @NotNull List<@Valid AccommodationWrite> accommodations,
                       @NotNull List<@Valid TransportWrite> transports,
                       @Size(max = 64) String timeZone) {}
    record StopWrite(@NotNull UUID destinationId, @Min(0) int position, LocalDate arrivalDate,
                     LocalDate departureDate) {}
    record ActivityWrite(@NotBlank String name, String description, @NotNull LocalDate date,
                         TravelDomain.ActivityCategory category) {}
    record AccommodationWrite(@NotBlank String name, @NotBlank String address, @NotNull LocalDate checkIn,
                              @NotNull LocalDate checkOut, TravelDomain.AccommodationCategory category) {}
    record TransportWrite(@NotBlank String mode, @NotBlank String departure, @NotBlank String arrival,
                          @NotNull LocalDate date) {}
}
