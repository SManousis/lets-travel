package com.travelplan.auth;
import com.travelplan.auth.security.TokenService;
import com.travelplan.auth.security.ProvisionedIdentityStore;
import com.travelplan.auth.security.IdentitySessionCoordinator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController
public class InternalRevocationController {
 private final TokenService tokens; private final ProvisionedIdentityStore identities; private final IdentitySessionCoordinator coordinator; private final String serviceToken;
 public InternalRevocationController(TokenService t,ProvisionedIdentityStore identities,IdentitySessionCoordinator coordinator,@Value("${security.internal-service-token:change-me-internal}") String s){tokens=t;this.identities=identities;this.coordinator=coordinator;serviceToken=s;}
 @PostMapping("/internal/revoke/{subject}") ResponseEntity<Void> revoke(@PathVariable String subject,@RequestHeader(value="X-Internal-Service-Token",required=false)String presented){if(!serviceToken.equals(presented))return ResponseEntity.status(403).build();return coordinator.execute(()->{identities.disableSubject(subject);tokens.revokeSubject(subject);return ResponseEntity.noContent().build();});}
}
