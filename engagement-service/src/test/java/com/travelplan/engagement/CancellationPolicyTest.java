package com.travelplan.engagement;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The traveler cutoff: allowed through the travel-local date three days before the start. */
class CancellationPolicyTest {
    private static final LocalDate START = LocalDate.of(2026, 11, 20);

    @Test void cutoffIsThreeCalendarDaysBeforeTheStart() {
        assertThat(CancellationPolicy.cutoff(START)).isEqualTo(LocalDate.of(2026, 11, 17));
    }

    @Test void fourDaysBeforeIsAllowed() {
        assertThat(mayCancel(LocalDateTime.of(2026, 11, 16, 12, 0), "Europe/Athens")).isTrue();
    }

    @Test void exactlyThreeDaysBeforeIsAllowedForTheWholeLocalDay() {
        assertThat(mayCancel(LocalDateTime.of(2026, 11, 17, 0, 0), "Europe/Athens")).isTrue();
        assertThat(mayCancel(LocalDateTime.of(2026, 11, 17, 23, 59, 59), "Europe/Athens")).isTrue();
    }

    @Test void lessThanThreeDaysBeforeIsRejected() {
        assertThat(mayCancel(LocalDateTime.of(2026, 11, 18, 0, 0), "Europe/Athens")).isFalse();
        assertThat(mayCancel(LocalDateTime.of(2026, 11, 19, 12, 0), "Europe/Athens")).isFalse();
    }

    @Test void startDateAndLaterAreRejected() {
        assertThat(mayCancel(LocalDateTime.of(2026, 11, 20, 8, 0), "Europe/Athens")).isFalse();
        assertThat(mayCancel(LocalDateTime.of(2026, 11, 25, 8, 0), "Europe/Athens")).isFalse();
    }

    @Test void theTravelsTimeZoneDecidesNotUtc() {
        // 2026-11-17T22:30Z: still the cutoff day in UTC and Los Angeles, already the 18th in Athens and Tokyo.
        Instant instant = Instant.parse("2026-11-17T22:30:00Z");
        assertThat(CancellationPolicy.travelerMayCancel(START, "UTC", instant)).isTrue();
        assertThat(CancellationPolicy.travelerMayCancel(START, "America/Los_Angeles", instant)).isTrue();
        assertThat(CancellationPolicy.travelerMayCancel(START, "Europe/Athens", instant)).isFalse();
        assertThat(CancellationPolicy.travelerMayCancel(START, "Asia/Tokyo", instant)).isFalse();
        // 2026-11-18T05:00Z is the 18th in UTC but still the 17th (21:00) in Los Angeles.
        Instant later = Instant.parse("2026-11-18T05:00:00Z");
        assertThat(CancellationPolicy.travelerMayCancel(START, "UTC", later)).isFalse();
        assertThat(CancellationPolicy.travelerMayCancel(START, "America/Los_Angeles", later)).isTrue();
    }

    @Test void daylightSavingChangeOnTheCutoffDayDoesNotMoveTheBoundary() {
        // Athens leaves summer time on 2026-10-25 (03:00 -> 02:00); the cutoff for a 28 October start is that day.
        LocalDate start = LocalDate.of(2026, 10, 28);
        ZoneId athens = ZoneId.of("Europe/Athens");
        Instant lastMinute = LocalDateTime.of(2026, 10, 25, 23, 59).atZone(athens).toInstant();
        Instant nextDay = LocalDateTime.of(2026, 10, 26, 0, 0).atZone(athens).toInstant();
        assertThat(CancellationPolicy.travelerMayCancel(start, "Europe/Athens", lastMinute)).isTrue();
        assertThat(CancellationPolicy.travelerMayCancel(start, "Europe/Athens", nextDay)).isFalse();
        // Spring forward in New York (2027-03-14): the cutoff day is 23 hours long and still fully allowed.
        LocalDate spring = LocalDate.of(2027, 3, 17);
        ZoneId newYork = ZoneId.of("America/New_York");
        assertThat(CancellationPolicy.travelerMayCancel(spring, "America/New_York",
                LocalDateTime.of(2027, 3, 14, 23, 59).atZone(newYork).toInstant())).isTrue();
        assertThat(CancellationPolicy.travelerMayCancel(spring, "America/New_York",
                LocalDateTime.of(2027, 3, 15, 0, 0).atZone(newYork).toInstant())).isFalse();
    }

    private static boolean mayCancel(LocalDateTime local, String zone) {
        return CancellationPolicy.travelerMayCancel(START, zone, local.atZone(ZoneId.of(zone)).toInstant());
    }
}
