package com.travelplan.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TravelStoreTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void durationIncludesBothDatesAndSharedDestinationCannotBeDeleted() {
        var store = new TravelStore(CLOCK);
        var destination = store.createDestination("Athens", "GR");
        var travel = store.create(UUID.randomUUID(), "Trip", "A complete trip", LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 3), 10, new BigDecimal("125.50"), "eur",
                List.of(new TravelDomain.Stop(UUID.randomUUID(), destination.id(), 0, null, null)),
                List.of(), List.of(), List.of());

        assertEquals(3, travel.durationDays());
        assertEquals("EUR", travel.currency());
        assertThrows(TravelStore.Conflict.class, () -> store.deleteDestination(destination.id()));
        store.delete(travel.id());
        store.deleteDestination(destination.id());
    }

    @Test
    void reversedDatesAndMissingStopsAreRejected() {
        var store = new TravelStore(CLOCK);
        var managerId = UUID.randomUUID();
        var destination = store.createDestination("Rome", "IT");
        var stop = new TravelDomain.Stop(UUID.randomUUID(), destination.id(), 0, null, null);

        assertConflict("INVALID_DATES", () -> store.create(managerId, "Bad", "Description",
                LocalDate.of(2026, 12, 2), LocalDate.of(2026, 12, 1), 2, BigDecimal.TEN, "EUR",
                List.of(stop), List.of(), List.of(), List.of()));
        assertConflict("DESTINATION_REQUIRED", () -> store.create(managerId, "Bad", "Description",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 2), 2, BigDecimal.TEN, "EUR",
                List.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void preservesManagerLifecycleAndParticipantsOnUpdate() {
        var store = new TravelStore(CLOCK);
        UUID managerId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        var destination = store.createDestination("Lisbon", "PT");
        var stop = new TravelDomain.Stop(UUID.randomUUID(), destination.id(), 0,
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 3));
        var created = store.create(managerId, "Original", "Original description", LocalDate.of(2026, 12, 1),
                LocalDate.of(2026, 12, 3), 4, new BigDecimal("99.90"), "EUR",
                List.of(stop), List.of(), List.of(), List.of());
        store.addParticipant(created.id(), travelerId);
        var published = store.publish(created.id());

        var updated = store.update(created.id(), "Updated", "Updated description", LocalDate.of(2026, 12, 1),
                LocalDate.of(2026, 12, 4), 5, new BigDecimal("109.90"), "EUR",
                List.of(stop), List.of(), List.of(), List.of());

        assertEquals(managerId, updated.managerId());
        assertEquals(TravelDomain.Status.PUBLISHED, updated.status());
        assertEquals(published.version() + 1, updated.version());
        assertEquals(java.util.Set.of(travelerId), updated.travelerIds());
    }

    private static void assertConflict(String code, Runnable action) {
        var error = assertThrows(TravelStore.Conflict.class, action::run);
        assertEquals(code, error.code);
    }
}
