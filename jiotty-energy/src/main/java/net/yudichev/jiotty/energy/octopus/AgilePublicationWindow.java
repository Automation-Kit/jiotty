package net.yudichev.jiotty.energy.octopus;

import com.google.common.annotations.VisibleForTesting;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.UK_ZONE;

/// Octopus' schedule for publishing Agile prices. An Agile day runs from 23:00 to 23:00 UK time, and Octopus publish the prices of each Agile day from 16:00 UK
/// time on the day before, usually within minutes and occasionally as late as 20:00.
public final class AgilePublicationWindow {
    /// The earliest time of day, UK time, at which Octopus publish the next Agile day's prices.
    static final LocalTime WINDOW_START = LocalTime.of(16, 0);
    /// The time of day, UK time, at which one Agile day ends and the next begins.
    private static final LocalTime DAY_BOUNDARY = LocalTime.of(23, 0);

    private AgilePublicationWindow() {
    }

    /// @return the time up to which Octopus should have published Agile prices by `now`: 23:00 UK time tomorrow if `now` is between 16:00 and 23:00 UK
    ///         time, and the next 23:00 UK time otherwise
    public static Instant expectedCoverage(Instant now) {
        Instant closesAt = closesAt(now);
        return isOpen(now) ? closesAt.atZone(UK_ZONE).plusDays(1).toInstant() : closesAt;
    }

    /// @return whether `now` is between [#WINDOW_START] and [#DAY_BOUNDARY], when Octopus may publish the next Agile day's prices at any moment
    static boolean isOpen(Instant now) {
        LocalTime localTime = now.atZone(UK_ZONE).toLocalTime();
        return !localTime.isBefore(WINDOW_START) && localTime.isBefore(DAY_BOUNDARY);
    }

    /// @return the end of the Agile day that `now` is in: the next [#DAY_BOUNDARY] after `now`
    @VisibleForTesting
    static Instant closesAt(Instant now) {
        ZonedDateTime localNow = now.atZone(UK_ZONE);
        LocalDate day = localNow.toLocalTime().isBefore(DAY_BOUNDARY) ? localNow.toLocalDate() : localNow.toLocalDate().plusDays(1);
        return day.atTime(DAY_BOUNDARY).atZone(UK_ZONE).toInstant();
    }

    /// @return the start of the last Agile day whose prices Octopus should have published by `now`: one day before [#expectedCoverage]
    static Instant expectedPublicationStart(Instant now) {
        return expectedCoverage(now).atZone(UK_ZONE).minusDays(1).toInstant();
    }

    /// @return the next [#WINDOW_START] after `now`
    static Instant nextWindowStartAfter(Instant now) {
        return OctopusPriceSlots.nextUkTimeOfDayAfter(now, WINDOW_START);
    }
}
