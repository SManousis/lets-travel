package com.travelplan.common.security;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OwnershipTest {
    private final UUID owner = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @Test void ownersAndAdminsMayAccessOthersMayNot() {
        assertThat(Ownership.canAccess(token(owner, Role.TRAVELER), owner)).isTrue();
        assertThat(Ownership.canAccess(token(other, Role.TRAVELER), owner)).isFalse();
        assertThat(Ownership.canAccess(token(other, Role.TRAVEL_MANAGER), owner)).as("managers get no access to other users' records").isFalse();
        assertThat(Ownership.canAccess(token(other, Role.ADMIN), owner)).isTrue();
    }

    @Test void listsAreScopedToTheCallerUnlessAdmin() {
        assertThat(Ownership.listScope(token(other, Role.TRAVELER), owner)).isEqualTo(other);
        assertThat(Ownership.listScope(token(other, Role.TRAVEL_MANAGER), null)).isEqualTo(other);
        assertThat(Ownership.listScope(token(other, Role.ADMIN), owner)).isEqualTo(owner);
        assertThat(Ownership.listScope(token(other, Role.ADMIN), null)).isNull();
    }

    private static AccessToken token(UUID user, Role role) {
        return new AccessToken(user, role, UUID.randomUUID(), UUID.randomUUID(), Instant.now(), Instant.now().plusSeconds(60));
    }
}
