package com.travelplan.users;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
final class UserDeletionService {
    private final UserStore store;
    private final RevocationClient revocations;
    private final UserDeletionClients clients;

    UserDeletionService(UserStore store, RevocationClient revocations, UserDeletionClients clients) {
        this.store = store;
        this.revocations = revocations;
        this.clients = clients;
    }

    synchronized boolean delete(UUID id) {
        synchronized (store) { return deleteLocked(id); }
    }

    private boolean deleteLocked(UUID id) {
        store.beginDeletionIfAllowed(id);
        String stage = store.deletionStage(id);
        for (int step = 0; step < 5; step++) {
            switch (stage) {
                case "STARTED" -> {
                    try { revocations.revoke(id); }
                    catch (UserStore.Conflict ex) { throw new UserDeletionClients.Unavailable("REVOCATION_UNAVAILABLE"); }
                    store.disableForDeletion(id);
                    store.advanceDeletion(id, "STARTED", "REVOKED");
                }
                case "REVOKED" -> {
                    // Before payments: cancelling paid subscriptions asks payment-service for their refunds.
                    if (!clients.cleanupEngagement(id)) return false;
                    store.advanceDeletion(id, "REVOKED", "ENGAGEMENT_CLEAN");
                }
                case "ENGAGEMENT_CLEAN" -> {
                    if (!clients.cleanupPayments(id)) return false;
                    store.advanceDeletion(id, "ENGAGEMENT_CLEAN", "PAYMENTS_CLEAN");
                }
                case "PAYMENTS_CLEAN" -> {
                    // Seat reservations and travel history are keyed by the account ID, profiles by their own IDs.
                    List<UUID> owned = new ArrayList<>(store.travelerIds(id));
                    owned.add(id);
                    clients.cleanupTravelers(owned);
                    store.advanceDeletion(id, "PAYMENTS_CLEAN", "TRAVEL_CLEAN");
                }
                case "TRAVEL_CLEAN" -> {
                    store.removeTravelersForUser(id);
                    store.advanceDeletion(id, "TRAVEL_CLEAN", "COMPLETE");
                }
                case "COMPLETE" -> { return true; }
                default -> throw new IllegalStateException("Unknown user deletion stage");
            }
            stage = store.deletionStage(id);
        }
        return "COMPLETE".equals(stage);
    }
}
