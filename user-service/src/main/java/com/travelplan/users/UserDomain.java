package com.travelplan.users;
import com.travelplan.common.security.Role;

import java.time.Instant;
import java.util.UUID;

public final class UserDomain {
    public enum Status { ACTIVE, DISABLED, PENDING, FAILED }
    public record User(UUID id, String email, String firstName, String lastName, Role role, Status status, Instant createdAt) {}
    public record Traveler(UUID id, UUID userId, String passportNumber, String nationality, Instant createdAt) {}
}
