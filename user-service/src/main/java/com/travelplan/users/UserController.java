package com.travelplan.users;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
public class UserController {
    private static final Logger log = LoggerFactory.getLogger(UserController.class);
    private final UserStore store;
    private final UserDeletionService deletion;
    private final UserDeletionClients cleanup;
    public UserController(UserStore store, UserDeletionService deletion, UserDeletionClients cleanup) {
        this.store = store; this.deletion = deletion; this.cleanup = cleanup;
    }
    @GetMapping("/api/users") public Page<UserDomain.User> users(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) { return page(store.list(), page, size); }
    @PostMapping("/api/users") public ResponseEntity<?> create(@Valid @RequestBody UserCreate body, @RequestHeader(value="Idempotency-Key", required=false) String key) { Object old=store.idempotent(key); if(old!=null)return ResponseEntity.ok(old); UserDomain.User u=store.create(body.email(),body.firstName(),body.lastName(),body.role()); store.remember(key,u); return ResponseEntity.status(HttpStatus.CREATED).body(u); }
    @GetMapping("/api/users/{id}") public UserDomain.User user(@PathVariable UUID id) { return store.get(id); }
    @PatchMapping("/api/users/{id}") public UserDomain.User update(@PathVariable UUID id,@Valid @RequestBody UserPatch body) { return store.update(id,body.email(),body.firstName(),body.lastName(),body.role(),body.status()); }
    @DeleteMapping("/api/users/{id}") public ResponseEntity<Void> delete(@PathVariable UUID id) {
        return deletion.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.accepted().build();
    }
    @GetMapping("/api/travelers") public Page<UserDomain.Traveler> travelers(@RequestParam(required=false) UUID userId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return page(store.travelers(userId),page,size);}
    @PostMapping("/api/travelers") public ResponseEntity<UserDomain.Traveler> createTraveler(@Valid @RequestBody TravelerCreate b){return ResponseEntity.status(HttpStatus.CREATED).body(store.createTraveler(b.userId(),b.passportNumber(),b.nationality()));}
    @GetMapping("/api/travelers/{id}") public UserDomain.Traveler traveler(@PathVariable UUID id){return store.traveler(id);}
    @PatchMapping("/api/travelers/{id}") public UserDomain.Traveler updateTraveler(@PathVariable UUID id,@Valid @RequestBody TravelerPatch b){return store.updateTraveler(id,b.passportNumber(),b.nationality());}
    @DeleteMapping("/api/travelers/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteTraveler(@PathVariable UUID id){
        store.traveler(id);
        cleanup.cleanupTravelers(List.of(id));
        store.deleteTraveler(id);
    }
    @ExceptionHandler(UserStore.NotFound.class) ResponseEntity<Error> notFound(){return ResponseEntity.status(404).body(new Error("NOT_FOUND","Resource not found"));}
    @ExceptionHandler(UserStore.Conflict.class) ResponseEntity<Error> conflict(UserStore.Conflict e){return ResponseEntity.status(409).body(new Error(e.code,"Request conflicts with current state"));}
    @ExceptionHandler(UserDeletionClients.Unavailable.class) ResponseEntity<Error> cleanupUnavailable(UserDeletionClients.Unavailable e){return ResponseEntity.status(503).body(new Error(e.code,"Cleanup dependency unavailable"));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<Error> storeUnavailable(org.springframework.dao.DataAccessException error){log.error("User storage operation failed", error);return ResponseEntity.status(503).body(new Error("USER_STORE_UNAVAILABLE","User storage unavailable"));}
    @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<Error> invalid(){return ResponseEntity.badRequest().body(new Error("INVALID_REQUEST","Request validation failed"));}
    private static <T> Page<T> page(List<T> all,int page,int size){if(page<0||size<1||size>100)throw new IllegalArgumentException();int from=Math.min(page*size,all.size());return new Page<>(all.subList(from,Math.min(from+size,all.size())),page,size,all.size());}
    public record Page<T>(List<T> items,int page,int size,long total) {}
    public record Error(String code,String message) {}
    public record UserCreate(@NotBlank @Email String email,@NotBlank String firstName,@NotBlank String lastName, UserDomain.Role role) { public UserCreate { if(role==null)role=UserDomain.Role.OPERATOR; } }
    public record UserPatch(@Email String email,String firstName,String lastName,UserDomain.Role role,UserDomain.Status status) {}
    public record TravelerCreate(@NotNull UUID userId,@NotBlank String passportNumber,@NotBlank String nationality) {}
    public record TravelerPatch(@NotBlank String passportNumber,@NotBlank String nationality) {}
}
