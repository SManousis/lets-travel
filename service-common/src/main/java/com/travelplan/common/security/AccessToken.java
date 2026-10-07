package com.travelplan.common.security;

import java.time.Instant;
import java.util.UUID;

/** The verified claims of an access token; used as the authenticated principal. */
public record AccessToken(UUID userId, Role role, UUID sessionId, UUID tokenId, Instant issuedAt, Instant expiresAt) {

    public boolean isUser(UUID id) {
        return userId.equals(id);
    }
}
