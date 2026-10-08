package com.travelplan.common.audit;

import com.travelplan.common.RequestIds;
import com.travelplan.common.security.AccessToken;
import com.travelplan.common.security.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditLogTest {
    private final List<AuditLog.AuditEntry> written = new ArrayList<>();
    private final AuditLog audit = new AuditLog("user-service", List.of(written::add),
            Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC));

    @AfterEach void signOut() { SecurityContextHolder.clearContext(); }

    @Test void recordsTheSignedInActorTheTargetAndTheRequest() {
        UUID admin = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        AccessToken token = new AccessToken(admin, Role.ADMIN, UUID.randomUUID(), UUID.randomUUID(), Instant.now(), Instant.now().plusSeconds(60));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(token, null, List.of()));

        RequestIds.within("req-42", () -> audit.record("USER_UPDATED", "USER", user, Map.of("role", "TRAVELER -> ADMIN")));

        assertThat(written).singleElement().satisfies(entry -> {
            assertThat(entry.actorId()).isEqualTo(admin);
            assertThat(entry.actorRole()).isEqualTo("ADMIN");
            assertThat(entry.action()).isEqualTo("USER_UPDATED");
            assertThat(entry.targetId()).isEqualTo(user.toString());
            assertThat(entry.requestId()).isEqualTo("req-42");
            assertThat(entry.occurredAt()).isEqualTo(Instant.parse("2026-10-08T10:00:00Z"));
            assertThat(entry.details()).isEqualTo("{\"role\":\"TRAVELER -> ADMIN\"}");
        });
    }

    @Test void workWithoutASignedInCallerIsRecordedAsSystem() {
        audit.record("REFUND_REQUESTED", "PAYMENT", UUID.randomUUID());
        assertThat(written.getFirst().actorId()).isNull();
        assertThat(written.getFirst().actorRole()).isEqualTo("SYSTEM");
    }

    @Test void detailsAreValidJsonWhateverTheyContain() {
        assertThat(AuditLog.json(Map.of("x", "a\"b\\c\n"))).isEqualTo("{\"x\":\"a\\\"b\\\\c\\u000a\"}");
        assertThat(AuditLog.json(Map.of("x", "y".repeat(2000)))).hasSize(1000).endsWith("...");
    }

    @Test void anActionFailsWhenItsRecordCannotBeKept() {
        AuditLog broken = new AuditLog("svc", List.of(entry -> { throw new IllegalStateException("database down"); }), Clock.systemUTC());
        assertThatThrownBy(() -> broken.record("USER_DELETED", "USER", UUID.randomUUID())).hasMessage("database down");
    }
}
