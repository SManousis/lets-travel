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
}
