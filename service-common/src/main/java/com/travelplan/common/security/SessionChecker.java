package com.travelplan.common.security;

/** Confirms that a token's session is still active (not logged out, revoked or demoted). */
@FunctionalInterface
public interface SessionChecker {
    enum Result { ACTIVE, DENIED, UNAVAILABLE }

    Result check(String bearerToken);
}
