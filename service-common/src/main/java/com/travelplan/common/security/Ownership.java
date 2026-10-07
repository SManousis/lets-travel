package com.travelplan.common.security;

import java.util.UUID;

/**
 * Resource ownership: admins may act on any resource, everyone else only on their own.
 * Callers turn a refusal into 404 so other users' resources are not revealed.
 */
public final class Ownership {
    private Ownership() {}

    public static boolean canAccess(AccessToken caller, UUID ownerId) {
        return caller.role() == Role.ADMIN || caller.userId().equals(ownerId);
    }

    /** The owner whose records a list request may see: anyone for admins, otherwise the caller. */
    public static UUID listScope(AccessToken caller, UUID requestedOwner) {
        return caller.role() == Role.ADMIN ? requestedOwner : caller.userId();
    }
}
