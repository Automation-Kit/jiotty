package net.yudichev.jiotty.connector.google.calendar;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.Events;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.StringFormattable;
import net.yudichev.jiotty.common.time.calendar.Calendar;
import net.yudichev.jiotty.common.time.calendar.CalendarEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.Temporal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.security.LogRedaction.redact;
import static net.yudichev.jiotty.common.time.calendar.CalendarEventIds.createContentDerivedId;

final class GoogleCalendar implements Calendar, StringFormattable {
    private static final Logger logger = LogManager.getLogger(GoogleCalendar.class);

    private final com.google.api.services.calendar.Calendar calendarApi;
    private final String id;
    private final String name;
    /// Redacted form of [#name], computed once: `name` is a calendar title (PII) that would otherwise be re-redacted on every log line.
    private final String redactedName;
    /// Redacted form of [#id], computed once for the same reason: a Google calendar id is the account's email address.
    private final String redactedId;
    private final SchedulingExecutor executor;

    public GoogleCalendar(com.google.api.services.calendar.Calendar calendarApi, String id, String name, SchedulingExecutor executor) {
        this.calendarApi = checkNotNull(calendarApi);
        this.id = checkNotNull(id);
        this.name = checkNotNull(name);
        redactedName = redact(name);
        redactedId = redact(id);
        this.executor = checkNotNull(executor);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public CompletableFuture<List<CalendarEvent>> fetchEvents(Instant from, Instant to) {
        return executor.submit(() -> {
            logger.debug("Calendar {}: fetching events for {}...{}", redactedName, from, to);
            var resultBuilder = ImmutableList.<CalendarEvent>builder();
            String pageToken = null;
            do {
                Events events = GoogleCalendarRequests.execute("fetch events for calendar " + redactedName,
                                                               calendarApi.events().list(id)
                                                                          .setTimeMin(new DateTime(from.toEpochMilli()))
                                                                          .setTimeMax(new DateTime(to.toEpochMilli()))
                                                                          .setSingleEvents(true)
                                                                          .setOrderBy("startTime")
                                                                          .setPageToken(pageToken));
                List<Event> items = events.getItems();
                if (items != null) {
                    for (Event event : items) {
                        toCalendarEvent(event).ifPresent(resultBuilder::add);
                    }
                }
                pageToken = events.getNextPageToken();
            } while (pageToken != null);
            var result = resultBuilder.build();
            logger.debug("Calendar {}: fetched {} events", redactedName, result.size());
            return result;
        });
    }

    /// Converts one item of a `singleEvents` listing, which is a single occurrence of its entry.
    ///
    /// @return empty for an event carrying no usable start or end
    @VisibleForTesting
    static Optional<CalendarEvent> toCalendarEvent(Event event) {
        Temporal start = toTemporal(event.getStart());
        Temporal end = toTemporal(event.getEnd());
        if (start == null || end == null) {
            return Optional.empty();
        }
        String summary = event.getSummary() == null ? "" : event.getSummary();
        return Optional.of(CalendarEvent.builder()
                                        // A singleEvents listing gives each occurrence of a recurring entry its own id, of the form
                                        // <recurringEventId>_<originalStartTime>, so this identifies the occurrence and not just the series.
                                        .setId(event.getId() == null ? createContentDerivedId(summary, event.getLocation()) : event.getId())
                                        .setStart(start)
                                        .setEnd(end)
                                        .setSummary(summary)
                                        .setDescription(event.getDescription())
                                        .setLocation(event.getLocation())
                                        .build());
    }

    /// Converts a Google [EventDateTime] to a [Temporal]: an [Instant] for a timed event, or a [LocalDate] for an all-day event. Returns `null` when the value
    /// is absent — `eventDateTime` itself is `null`, or it carries neither a timed nor an all-day value — so the caller skips the malformed event.
    private static @Nullable Temporal toTemporal(@Nullable EventDateTime eventDateTime) {
        if (eventDateTime == null) {
            return null;
        }
        DateTime dateTime = eventDateTime.getDateTime();
        if (dateTime != null) {
            return Instant.ofEpochMilli(dateTime.getValue());
        }
        DateTime date = eventDateTime.getDate();
        return date == null ? null : LocalDate.parse(date.toStringRfc3339());
    }

    @Override
    public String toString() {
        return toString(48);
    }

    @Override
    public void formatTo(Appendable appendable) {
        Append.to(appendable, "GoogleCalendar{id='");
        Append.to(appendable, redactedId);
        Append.to(appendable, "', name='");
        Append.to(appendable, redactedName);
        Append.to(appendable, "'}");
    }
}
