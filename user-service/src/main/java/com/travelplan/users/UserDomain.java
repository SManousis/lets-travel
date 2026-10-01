package com.travelplan.users;

import java.time.Instant;
import java.util.UUID;

public final class UserDomain {
    public enum Role { ADMIN, OPERATOR }
    public enum Status { ACTIVE, DISABLED, PENDING, FAILED }
    public record User(UUID id, String email, String firstName, String lastName, Role role, Status status, Instant createdAt) {}
    public record Traveler(UUID id, UUID userId, String passportNumber, String nationality, Instant createdAt) {}
}
