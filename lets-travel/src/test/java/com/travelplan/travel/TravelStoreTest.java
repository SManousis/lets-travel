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
                LocalDate.of(2026, 12, 3), 5, new BigDecimal("99.90"), "EUR",
                List.of(stop), List.of(), List.of(), List.of());

        assertEquals(managerId, updated.managerId());
        assertEquals(TravelDomain.Status.PUBLISHED, updated.status());
        assertEquals(published.version() + 1, updated.version());
        assertEquals(java.util.Set.of(travelerId), updated.travelerIds());
    }

    @Test
    void bookedDatesZoneAndPriceAreFixedOncePlacesAreTaken() {
        var store = new TravelStore(CLOCK);
        var destination = store.createDestination("Porto", "PT");
        var stop = new TravelDomain.Stop(UUID.randomUUID(), destination.id(), 0, null, null);
        var created = store.create(UUID.randomUUID(), "Porto", "Description", LocalDate.of(2026, 12, 1),
                LocalDate.of(2026, 12, 3), 4, new BigDecimal("99.90"), "EUR", List.of(stop), List.of(), List.of(),
                List.of(), "Europe/Lisbon");
        store.publish(created.id());
        store.reserveSeat(created.id(), UUID.randomUUID());

        assertConflict("TRAVEL_HAS_SUBSCRIBERS", () -> store.update(created.id(), "Porto", "Description",
                LocalDate.of(2026, 12, 2), LocalDate.of(2026, 12, 3), 4, new BigDecimal("99.90"), "EUR",
                List.of(stop), List.of(), List.of(), List.of()));
        assertConflict("TRAVEL_HAS_SUBSCRIBERS", () -> store.update(created.id(), "Porto", "Description",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 3), 4, new BigDecimal("89.90"), "EUR",
                List.of(stop), List.of(), List.of(), List.of()));
        assertConflict("TRAVEL_HAS_SUBSCRIBERS", () -> store.update(created.id(), "Porto", "Description",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 3), 4, new BigDecimal("99.90"), "EUR",
                List.of(stop), List.of(), List.of(), List.of(), "Asia/Tokyo"));
        var renamed = store.update(created.id(), "Porto weekend", "New description", LocalDate.of(2026, 12, 1),
                LocalDate.of(2026, 12, 3), 6, new BigDecimal("99.900"), "EUR", List.of(stop), List.of(), List.of(),
                List.of());
        assertEquals("Porto weekend", renamed.name());
        assertEquals("Europe/Lisbon", renamed.timeZone());
    }

    @Test
    void invalidTimeZoneIsRejected() {
        var store = new TravelStore(CLOCK);
        var destination = store.createDestination("Oslo", "NO");
        assertConflict("INVALID_TIME_ZONE", () -> store.create(UUID.randomUUID(), "Oslo", "Description",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 2), 2, BigDecimal.TEN, "NOK",
                List.of(new TravelDomain.Stop(UUID.randomUUID(), destination.id(), 0, null, null)),
                List.of(), List.of(), List.of(), "Mars/Olympus"));
    }

    private static void assertConflict(String code, Runnable action) {
        var error = assertThrows(TravelStore.Conflict.class, action::run);
        assertEquals(code, error.code);
    }
}
