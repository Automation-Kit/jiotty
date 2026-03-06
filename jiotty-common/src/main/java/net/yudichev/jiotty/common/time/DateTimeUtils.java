package net.yudichev.jiotty.common.time;

import com.google.common.collect.ImmutableList;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.chrono.ChronoZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.BiPredicate;

import static com.google.common.base.Preconditions.checkArgument;

public final class DateTimeUtils {
    private DateTimeUtils() {
    }

    public static ZonedDateTime toZonedDateTimeInFuture(ZonedDateTime zonedNow, LocalTime localTime) {
        return toZonedDateTimeInFuture0(zonedNow, localTime, (result, now) -> !result.isAfter(now));
    }

    public static ZonedDateTime toZonedDateTimeNowOrInFuture(ZonedDateTime zonedNow, LocalTime localTime) {
        return toZonedDateTimeInFuture0(zonedNow, localTime, ChronoZonedDateTime::isBefore);
    }

    private static ZonedDateTime toZonedDateTimeInFuture0(ZonedDateTime zonedNow,
                                                          LocalTime localTime,
                                                          BiPredicate<ZonedDateTime, ZonedDateTime> isInThPast) {
        ZonedDateTime zonedResult = zonedNow.with(localTime);
        if (isInThPast.test(zonedResult, zonedNow)) {
            zonedResult = zonedResult.plusDays(1);
        }
        return zonedResult;
    }

    /// @return a list of `[start, end)` intervals clipped to `[from, to)`
    public static List<TimeInterval> getTimeIntervalsMatchingStartEndTime(ZonedDateTime from, ZonedDateTime to, LocalTime start, LocalTime end) {
        checkArgument(from.getZone().equals(to.getZone()), "from and to must be in the same time zone but were %s and %s", from.getZone(), to.getZone());
        if (!from.isBefore(to)) {
            return List.of();
        }
        if (start.equals(end)) {
            return List.of();
        }

        ZoneId zone = from.getZone();
        boolean overnight = end.isBefore(start);

        var resultBuilder = ImmutableList.<TimeInterval>builderWithExpectedSize(4);

        LocalDate fromDate = from.toLocalDate();
        LocalDate toDate = to.toLocalDate();
        LocalDate date = overnight ? fromDate.minusDays(1) : fromDate;
        for (; !date.isAfter(toDate); date = date.plusDays(1)) {
            ZonedDateTime windowStart = date.atTime(start).atZone(zone);
            ZonedDateTime windowEnd = (overnight ? date.plusDays(1) : date).atTime(end).atZone(zone);

            ZonedDateTime intervalStart = windowStart.isAfter(from) ? windowStart : from;
            ZonedDateTime intervalEnd = windowEnd.isBefore(to) ? windowEnd : to;

            if (intervalStart.isBefore(intervalEnd)) {
                resultBuilder.add(new TimeInterval(intervalStart.toInstant(), intervalEnd.toInstant()));
            }
        }

        return resultBuilder.build();
    }

    public static final class Formatter {
        private final DateTimeFormatter fullDateAndTimeMinsFormatter;
        private final DateTimeFormatter timeOnlyMinsFormatter;
        private final DateTimeFormatter fullDateAndTimeSecFormatter;
        private final DateTimeFormatter dayAndTimeMinsFormatter;

        @Inject
        public Formatter(ZoneId zoneId) {
            fullDateAndTimeMinsFormatter = DateTimeFormatter.ofPattern("dd MMM yy HH:mm").withZone(zoneId);
            fullDateAndTimeSecFormatter = DateTimeFormatter.ofPattern("dd MMM yy HH:mm:ss").withZone(zoneId);
            dayAndTimeMinsFormatter = DateTimeFormatter.ofPattern("dd HH:mm").withZone(zoneId);
            timeOnlyMinsFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(zoneId);
        }

        public String toFullDateAndTimeMins(Instant instant) {
            return fullDateAndTimeMinsFormatter.format(instant);
        }

        public String toFullDateAndTimeSec(Instant instant) {
            return fullDateAndTimeSecFormatter.format(instant);
        }

        public String toDayAndTimeMins(Instant instant) {
            return dayAndTimeMinsFormatter.format(instant);
        }

        public String toTimeOnlyMins(Instant instant) {
            return timeOnlyMinsFormatter.format(instant);
        }
    }
}
