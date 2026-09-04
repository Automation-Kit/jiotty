package net.yudichev.jiotty.energy.octopus;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/// Octopus's publication contract for Agile day-ahead prices, stated once because retrieval consults it from several places and boils it down to the one
/// instant consumers see. Octopus publish tomorrow's rates from [#WINDOW_START] — usually within minutes, occasionally as late as 20:00 — against an Agile day
/// that rolls over at [#DAY_BOUNDARY].
final class AgilePublicationWindow {
    /// Octopus publish on UK time wherever the process happens to run.
    static final ZoneId ZONE = ZoneId.of("Europe/London");
    /// The earliest Octopus publish tomorrow's rates; before this, a profile covering only today is expected rather than late.
    static final LocalTime WINDOW_START = LocalTime.of(16, 0);
    /// The time of day an Agile day rolls over, so also the point past which "tomorrow's prices" names a different day.
    private static final LocalTime DAY_BOUNDARY = LocalTime.of(23, 0);

    private AgilePublicationWindow() {
    }

    /// @return whether `now` falls at or after [#WINDOW_START] and before [#DAY_BOUNDARY], the band in which tomorrow's prices may appear at any moment
    static boolean isOpen(Instant now) {
        LocalTime localTime = now.atZone(ZONE).toLocalTime();
        return !localTime.isBefore(WINDOW_START) && localTime.isBefore(DAY_BOUNDARY);
    }

    /// @return the end of the Agile day containing `now` — the first [#DAY_BOUNDARY] strictly after it
    static Instant closesAt(Instant now) {
        ZonedDateTime localNow = now.atZone(ZONE);
        LocalDate day = localNow.toLocalTime().isBefore(DAY_BOUNDARY) ? localNow.toLocalDate() : localNow.toLocalDate().plusDays(1);
        return day.atTime(DAY_BOUNDARY).atZone(ZONE).toInstant();
    }

    /// @return how far a profile should reach given everything Octopus ought to have published by `now` — one Agile day past [#closesAt] once a window has
    /// opened, [#closesAt] itself before that. The value is continuous across [#DAY_BOUNDARY]: the day that rolls over there is the one just published.
    static Instant expectedCoverage(Instant now) {
        Instant closesAt = closesAt(now);
        return isOpen(now) ? closesAt.atZone(ZONE).plusDays(1).toInstant() : closesAt;
    }

    /// @return where the Agile day named by [#expectedCoverage] begins — one day earlier. A profile ending before this has a gap that the publication in
    /// question starts after, so waiting for that publication will not close it.
    static Instant expectedPublicationStart(Instant now) {
        return expectedCoverage(now).atZone(ZONE).minusDays(1).toInstant();
    }

    /// @return the start of the first publication window beginning strictly after `now`, so a `now` already inside one yields the following day's
    static Instant nextWindowStartAfter(Instant now) {
        ZonedDateTime localNow = now.atZone(ZONE);
        ZonedDateTime todaysStart = localNow.toLocalDate().atTime(WINDOW_START).atZone(ZONE);
        return (localNow.isBefore(todaysStart) ? todaysStart : todaysStart.plusDays(1)).toInstant();
    }
}
