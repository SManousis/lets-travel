package com.travelplan.travel;
import jakarta.validation.Valid; import jakarta.validation.constraints.*; import java.time.*; import java.util.*; import org.springframework.http.*; import org.springframework.web.bind.annotation.*; import org.springframework.web.bind.MethodArgumentNotValidException;
@RestController public class TravelController {
 private final TravelStore store; public TravelController(TravelStore s){store=s;}
 @GetMapping("/api/destinations") Page<TravelDomain.Destination> destinations(@RequestParam(defaultValue="0")int p,@RequestParam(defaultValue="20")int size){return page(store.destinations(),p,size);}
 @PostMapping("/api/destinations") ResponseEntity<TravelDomain.Destination> createDestination(@Valid @RequestBody DestinationWrite b){var d=store.createDestination(b.name(),b.country());return ResponseEntity.status(201).body(d);}
 @GetMapping("/api/destinations/{id}") TravelDomain.Destination destination(@PathVariable UUID id){return store.destination(id);}
 @PatchMapping("/api/destinations/{id}") TravelDomain.Destination updateDestination(@PathVariable UUID id,@Valid @RequestBody DestinationWrite b){return store.updateDestination(id,b.name(),b.country());}
 @DeleteMapping("/api/destinations/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void deleteDestination(@PathVariable UUID id){store.deleteDestination(id);}
 @GetMapping("/api/travels") Page<TravelDomain.Travel> travels(@RequestParam(defaultValue="0")int p,@RequestParam(defaultValue="20")int size){return page(store.travels(),p,size);}
 @PostMapping("/api/travels") ResponseEntity<TravelDomain.Travel> createTravel(@Valid @RequestBody TravelWrite b){var t=store.save(b.name(),b.startDate(),b.endDate(),stops(b.stops()),activities(b.activities()),accommodations(b.accommodations()),transports(b.transports()));return ResponseEntity.status(201).body(t);}
 @GetMapping("/api/travels/{id}") TravelDomain.Travel travel(@PathVariable UUID id){return store.travel(id);}
 @PatchMapping("/api/travels/{id}") TravelDomain.Travel updateTravel(@PathVariable UUID id,@Valid @RequestBody TravelWrite b){return store.update(id,b.name(),b.startDate(),b.endDate(),stops(b.stops()),activities(b.activities()),accommodations(b.accommodations()),transports(b.transports()));}
 @DeleteMapping("/api/travels/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void deleteTravel(@PathVariable UUID id){store.delete(id);}
 @PostMapping("/api/travels/{id}/travelers/{travelerId}") TravelDomain.Travel add(@PathVariable UUID id,@PathVariable UUID travelerId){return store.addParticipant(id,travelerId);}
 @DeleteMapping("/api/travels/{id}/travelers/{travelerId}") @ResponseStatus(HttpStatus.NO_CONTENT) void remove(@PathVariable UUID id,@PathVariable UUID travelerId){store.removeParticipant(id,travelerId);}
 @ExceptionHandler(TravelStore.NotFound.class) ResponseEntity<Error> missing(){return ResponseEntity.status(404).body(new Error("NOT_FOUND","Resource not found"));}
 @ExceptionHandler(TravelStore.Conflict.class) ResponseEntity<Error> conflict(TravelStore.Conflict e){return ResponseEntity.status(409).body(new Error(e.code,"Request conflicts with current state"));}
 @ExceptionHandler(org.neo4j.driver.exceptions.Neo4jException.class) ResponseEntity<Error> graphUnavailable(){return ResponseEntity.status(503).body(new Error("TRAVEL_STORE_UNAVAILABLE","Travel storage unavailable"));}
 @ExceptionHandler({MethodArgumentNotValidException.class,IllegalArgumentException.class}) ResponseEntity<Error> invalid(){return ResponseEntity.badRequest().body(new Error("INVALID_REQUEST","Request validation failed"));}
 private static <T> Page<T> page(List<T> all,int p,int size){if(p<0||size<1||size>100)throw new IllegalArgumentException();int from=Math.min(p*size,all.size());return new Page<>(all.subList(from,Math.min(from+size,all.size())),p,size,all.size());}
 private static List<TravelDomain.Stop> stops(List<StopWrite>x){return x.stream().map(s->new TravelDomain.Stop(UUID.randomUUID(),s.destinationId(),s.position(),s.arrivalDate(),s.departureDate())).toList();}
 private static List<TravelDomain.Activity> activities(List<ActivityWrite>x){return x.stream().map(a->new TravelDomain.Activity(UUID.randomUUID(),a.name(),a.description(),a.date())).toList();}
 private static List<TravelDomain.Accommodation> accommodations(List<AccommodationWrite>x){return x.stream().map(a->new TravelDomain.Accommodation(UUID.randomUUID(),a.name(),a.address(),a.checkIn(),a.checkOut())).toList();}
 private static List<TravelDomain.Transport> transports(List<TransportWrite>x){return x.stream().map(a->new TravelDomain.Transport(UUID.randomUUID(),a.mode(),a.departure(),a.arrival(),a.date())).toList();}
 record Page<T>(List<T> items,int page,int size,long total){} record Error(String code,String message){}
 record DestinationWrite(@NotBlank String name,@NotBlank String country){}
 record TravelWrite(@NotBlank String name,@NotNull LocalDate startDate,@NotNull LocalDate endDate,@NotEmpty List<StopWrite> stops,@NotNull List<ActivityWrite> activities,@NotNull List<AccommodationWrite> accommodations,@NotNull List<TransportWrite> transports){}
 record StopWrite(@NotNull UUID destinationId,@Min(0) int position,LocalDate arrivalDate,LocalDate departureDate){}
 record ActivityWrite(@NotBlank String name,String description,@NotNull LocalDate date){}
 record AccommodationWrite(@NotBlank String name,@NotBlank String address,@NotNull LocalDate checkIn,@NotNull LocalDate checkOut){}
 record TransportWrite(@NotBlank String mode,@NotBlank String departure,@NotBlank String arrival,@NotNull LocalDate date){}
}
