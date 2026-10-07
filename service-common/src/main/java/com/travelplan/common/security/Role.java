package com.travelplan.common.security;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Platform roles, lowest first. A role includes every role below it:
 * an ADMIN can do everything a TRAVEL_MANAGER can, who can do everything a TRAVELER can.
 */
public enum Role {
    TRAVELER, TRAVEL_MANAGER, ADMIN;

    public boolean includes(Role other) {
        return compareTo(other) >= 0;
    }

    /** Spring Security authorities for this role and every role it includes. */
    public List<String> authorities() {
        return Arrays.stream(values()).filter(this::includes).map(role -> "ROLE_" + role.name()).toList();
    }

    public static Optional<Role> parse(String value) {
        return Arrays.stream(values()).filter(role -> role.name().equals(value)).findFirst();
    }
}
