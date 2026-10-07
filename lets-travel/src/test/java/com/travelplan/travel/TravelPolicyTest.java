package com.travelplan.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TravelPolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final TravelPolicy policy = new TravelPolicy(CLOCK);

    @Test
    void rejectsInvalidCapacityPriceCurrencyAndDates() {
        assertConflict("INVALID_CAPACITY", travel(LocalDate.of(2026, 11, 1), 0, BigDecimal.TEN, "EUR", "Ready"));
        assertConflict("INVALID_PRICE", travel(LocalDate.of(2026, 11, 1), 2, new BigDecimal("-0.01"), "EUR", "Ready"));
        assertConflict("INVALID_CURRENCY", travel(LocalDate.of(2026, 11, 1), 2, BigDecimal.TEN, "EU", "Ready"));

        var valid = travel(LocalDate.of(2026, 11, 1), 2, BigDecimal.TEN, "EUR", "Ready");
        var reversed = new TravelDomain.Travel(valid.id(), valid.managerId(), valid.name(), valid.description(),
                valid.startDate(), valid.startDate().minusDays(1), valid.durationDays(), valid.capacity(),
                valid.price(), valid.currency(), valid.status(), valid.version(), valid.stops(), valid.activities(),
                valid.accommodations(), valid.transports(), valid.travelerIds(), valid.createdAt(), valid.updatedAt());
        assertConflict("INVALID_DATES", reversed);
    }

    @Test
    void requiresFutureCompleteTravelForPublication() {
        assertPublicationConflict(travel(LocalDate.of(2026, 10, 7), 2, BigDecimal.TEN, "EUR", "Ready"));
        assertPublicationConflict(travel(LocalDate.of(2026, 10, 8), 2, BigDecimal.TEN, "EUR", " "));
    }

    @Test
    void computesBookabilityAtTodayBoundary() {
        var future = withStatus(travel(LocalDate.of(2026, 10, 8), 2, BigDecimal.TEN, "EUR", "Ready"),
                TravelDomain.Status.PUBLISHED, Set.of(UUID.randomUUID()));
        var today = withStatus(travel(LocalDate.of(2026, 10, 7), 2, BigDecimal.TEN, "EUR", "Ready"),
                TravelDomain.Status.PUBLISHED, Set.of());
        var full = withStatus(travel(LocalDate.of(2026, 10, 8), 1, BigDecimal.TEN, "EUR", "Ready"),
                TravelDomain.Status.PUBLISHED, Set.of(UUID.randomUUID()));

        assertTrue(policy.bookable(future));
        assertEquals(1, policy.remainingCapacity(future));
        assertFalse(policy.bookable(today));
        assertFalse(policy.bookable(full));
        assertEquals(0, policy.remainingCapacity(full));
    }

    private static TravelDomain.Travel travel(LocalDate start, int capacity, BigDecimal price, String currency,
                                               String description) {
        var stop = new TravelDomain.Stop(UUID.randomUUID(), UUID.randomUUID(), 0, start, start.plusDays(1));
        return new TravelDomain.Travel(UUID.randomUUID(), UUID.randomUUID(), "Trip", description, start,
                start.plusDays(1), 2, capacity, price, currency, TravelDomain.Status.DRAFT, 1,
                List.of(stop), List.of(), List.of(), List.of(), Set.of(), NOW, NOW);
    }

    private static TravelDomain.Travel withStatus(TravelDomain.Travel source, TravelDomain.Status status,
                                                   Set<UUID> travelers) {
        return new TravelDomain.Travel(source.id(), source.managerId(), source.name(), source.description(),
                source.startDate(), source.endDate(), source.durationDays(), source.capacity(), source.price(),
                source.currency(), status, source.version(), source.stops(), source.activities(),
                source.accommodations(), source.transports(), travelers, source.createdAt(), source.updatedAt());
    }

    private void assertConflict(String code, TravelDomain.Travel travel) {
        var error = assertThrows(TravelStore.Conflict.class, () -> policy.validateDraft(travel));
        assertEquals(code, error.code);
    }

    private void assertPublicationConflict(TravelDomain.Travel travel) {
        var error = assertThrows(TravelStore.Conflict.class, () -> policy.validateForPublication(travel));
        assertEquals("PUBLICATION_NOT_READY", error.code);
    }
}
