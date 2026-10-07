package com.travelplan.engagement;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The traveler cancellation rule: a traveler may cancel up to and including the calendar day that
 * is three days before the travel starts, judged in the travel's own time zone. Calendar dates are
 * compared, never durations, so daylight-saving changes cannot move the boundary.
 */
final class CancellationPolicy {
    static final int CUTOFF_DAYS = 3;

    private CancellationPolicy() {}

    /** The last travel-local date on which the traveler may still cancel. */
    static LocalDate cutoff(LocalDate startDate) {
        return startDate.minusDays(CUTOFF_DAYS);
    }

    static boolean travelerMayCancel(LocalDate startDate, String timeZone, Instant now) {
        return !localDate(now, timeZone).isAfter(cutoff(startDate));
    }

    static LocalDate localDate(Instant now, String timeZone) {
        return LocalDate.ofInstant(now, ZoneId.of(timeZone));
    }
}
