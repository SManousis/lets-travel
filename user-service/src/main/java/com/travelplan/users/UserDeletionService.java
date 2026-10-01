package com.travelplan.users;

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
                    if (!clients.cleanupPayments(id)) return false;
                    store.advanceDeletion(id, "REVOKED", "PAYMENTS_CLEAN");
                }
                case "PAYMENTS_CLEAN" -> {
                    clients.cleanupTravelers(store.travelerIds(id));
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
