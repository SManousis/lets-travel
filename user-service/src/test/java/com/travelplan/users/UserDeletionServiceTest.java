package com.travelplan.users;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserDeletionServiceTest {
    @Test void travelCleanupCoversTheAccountAsWellAsItsTravelerProfiles() {
        UserStore store = mock(UserStore.class);
        UserDeletionClients clients = mock(UserDeletionClients.class);
        UUID user = UUID.randomUUID();
        UUID profile = UUID.randomUUID();
        when(store.deletionStage(user)).thenReturn("PAYMENTS_CLEAN", "TRAVEL_CLEAN", "COMPLETE");
        when(store.travelerIds(user)).thenReturn(List.of(profile));

        boolean done = new UserDeletionService(store, mock(RevocationClient.class), clients).delete(user);

        assertThat(done).isTrue();
        // Seat reservations and recommendation history are keyed by the account ID itself.
        verify(clients).cleanupTravelers(List.of(profile, user));
    }

    @Test void subscriptionsAndFeedbackAreCleanedBeforePaymentsAndTheDeletionWaitsForRefunds() {
        UserStore store = mock(UserStore.class);
        UserDeletionClients clients = mock(UserDeletionClients.class);
        UUID user = UUID.randomUUID();
        when(store.deletionStage(user)).thenReturn("REVOKED");
        when(clients.cleanupEngagement(user)).thenReturn(false);

        boolean done = new UserDeletionService(store, mock(RevocationClient.class), clients).delete(user);

        assertThat(done).as("a refund is still being requested; the deletion is retried").isFalse();
        verify(clients, org.mockito.Mockito.never()).cleanupPayments(user);

        when(clients.cleanupEngagement(user)).thenReturn(true);
        when(clients.cleanupPayments(user)).thenReturn(true);
        when(store.deletionStage(user)).thenReturn("REVOKED", "ENGAGEMENT_CLEAN", "PAYMENTS_CLEAN", "TRAVEL_CLEAN", "COMPLETE");
        assertThat(new UserDeletionService(store, mock(RevocationClient.class), clients).delete(user)).isTrue();
        var order = org.mockito.Mockito.inOrder(clients, store);
        order.verify(clients).cleanupEngagement(user);
        order.verify(store).advanceDeletion(user, "REVOKED", "ENGAGEMENT_CLEAN");
        order.verify(clients).cleanupPayments(user);
        order.verify(store).advanceDeletion(user, "ENGAGEMENT_CLEAN", "PAYMENTS_CLEAN");
    }
}
