package com.travelplan.travel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.neo4j.driver.exceptions.Neo4jException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class TravelCleanupController {
    private final TravelStore store;
    private final byte[] serviceToken;

    TravelCleanupController(TravelStore store,
            @Value("${security.internal-service-token:change-me-internal}") String serviceToken) {
        this.store = store;
        this.serviceToken = serviceToken.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/internal/travelers/cleanup")
    ResponseEntity<Void> cleanup(@RequestHeader(value = "X-Internal-Service-Token", required = false) String presented,
                                 @RequestBody List<UUID> travelerIds) {
        if (presented == null || !MessageDigest.isEqual(serviceToken, presented.getBytes(StandardCharsets.UTF_8)))
            return ResponseEntity.status(403).build();
        if (travelerIds == null || travelerIds.contains(null)) return ResponseEntity.badRequest().build();
        store.removeParticipants(new HashSet<>(travelerIds));
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(Neo4jException.class)
    ResponseEntity<Void> unavailable() { return ResponseEntity.status(503).build(); }
}
