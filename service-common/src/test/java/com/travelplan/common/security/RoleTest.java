package com.travelplan.common.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoleTest {
    @Test void higherRolesIncludeLowerOnes() {
        assertThat(Role.ADMIN.includes(Role.TRAVEL_MANAGER)).isTrue();
        assertThat(Role.ADMIN.includes(Role.TRAVELER)).isTrue();
        assertThat(Role.TRAVEL_MANAGER.includes(Role.TRAVELER)).isTrue();
        assertThat(Role.TRAVELER.includes(Role.TRAVELER)).isTrue();
    }

    @Test void lowerRolesNeverIncludeHigherOnes() {
        assertThat(Role.TRAVELER.includes(Role.TRAVEL_MANAGER)).isFalse();
        assertThat(Role.TRAVELER.includes(Role.ADMIN)).isFalse();
        assertThat(Role.TRAVEL_MANAGER.includes(Role.ADMIN)).isFalse();
    }

    @Test void authoritiesListTheRoleAndEverythingBelowIt() {
        assertThat(Role.ADMIN.authorities()).containsExactly("ROLE_TRAVELER", "ROLE_TRAVEL_MANAGER", "ROLE_ADMIN");
        assertThat(Role.TRAVEL_MANAGER.authorities()).containsExactly("ROLE_TRAVELER", "ROLE_TRAVEL_MANAGER");
        assertThat(Role.TRAVELER.authorities()).containsExactly("ROLE_TRAVELER");
    }

    @Test void parsesOnlyKnownRoles() {
        assertThat(Role.parse("TRAVEL_MANAGER")).contains(Role.TRAVEL_MANAGER);
        assertThat(Role.parse("OPERATOR")).isEmpty();
        assertThat(Role.parse("admin")).isEmpty();
        assertThat(Role.parse(null)).isEmpty();
    }
}
