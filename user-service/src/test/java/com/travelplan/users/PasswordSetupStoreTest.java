package com.travelplan.users;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordSetupStoreTest {
    private final Instant now = Instant.parse("2026-10-07T09:00:00Z");
    private final UUID user = UUID.randomUUID();

    @Test void tokenIsValidUntilItExpires() {
        PasswordSetupStore issuing = new PasswordSetupStore((JdbcTemplate) null, Clock.fixed(now, ZoneOffset.UTC));
        PasswordSetupStore.Issued issued = issuing.issue(user);

        assertThat(issued.expiresAt()).isEqualTo(now.plus(PasswordSetupStore.LIFETIME));
        assertThat(issuing.findValidForUpdate(issued.token())).contains(user);
    }

    @Test void tokenIsRejectedAfterUse() {
        PasswordSetupStore store = new PasswordSetupStore((JdbcTemplate) null, Clock.fixed(now, ZoneOffset.UTC));
        PasswordSetupStore.Issued used = store.issue(user);
        store.markUsed(used.token());
        assertThat(store.findValidForUpdate(used.token())).isEmpty();
        assertThat(store.findValidForUpdate(null)).isEmpty();
        assertThat(store.findValidForUpdate(" ")).isEmpty();
    }

    @Test void tokenIsRejectedOnceItsLifetimeHasPassed() {
        MovableClock clock = new MovableClock(now);
        PasswordSetupStore store = new PasswordSetupStore((JdbcTemplate) null, clock);
        PasswordSetupStore.Issued issued = store.issue(user);

        clock.set(issued.expiresAt().minusSeconds(1));
        assertThat(store.findValidForUpdate(issued.token())).contains(user);
        clock.set(issued.expiresAt());
        assertThat(store.findValidForUpdate(issued.token())).isEmpty();
    }

    @Test void issuingANewTokenInvalidatesEarlierOnes() {
        PasswordSetupStore store = new PasswordSetupStore((JdbcTemplate) null, Clock.fixed(now, ZoneOffset.UTC));
        PasswordSetupStore.Issued first = store.issue(user);
        PasswordSetupStore.Issued second = store.issue(user);
        PasswordSetupStore.Issued otherUser = store.issue(UUID.randomUUID());

        assertThat(store.findValidForUpdate(first.token())).isEmpty();
        assertThat(store.findValidForUpdate(second.token())).contains(user);
        assertThat(store.findValidForUpdate(otherUser.token())).isPresent();
    }

    private static final class MovableClock extends Clock {
        private Instant instant;
        MovableClock(Instant instant) { this.instant = instant; }
        void set(Instant next) { instant = next; }
        @Override public Instant instant() { return instant; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }

    @Test void tokensAreStoredOnlyAsHashes() {
        assertThat(PasswordSetupStore.hash("token")).hasSize(64).doesNotContain("token");
    }
}
