package net.yudichev.jiotty.connector.icloud.calendar;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.data.UnfoldingReader;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Content;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.DtEnd;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.Summary;
import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.StringFormattable;
import net.yudichev.jiotty.common.time.calendar.Calendar;
import net.yudichev.jiotty.common.time.calendar.CalendarEvent;
import okhttp3.HttpUrl;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.temporal.Temporal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.security.LogRedaction.redact;
import static net.yudichev.jiotty.common.time.calendar.CalendarEventIds.createContentDerivedId;

final class IcloudCalendar implements Calendar, StringFormattable {
    private static final Logger logger = LogManager.getLogger(IcloudCalendar.class);

    private final String id;
    private final String name;
    /// Redacted form of [#name], computed once: `name` is a calendar title (PII) that would otherwise be re-redacted on every log line.
    private final String redactedName;
    /// Redacted form of [#id], computed once for the same reason: a CalDAV href starts with the iCloud account id.
    private final String redactedId;
    private final HttpUrl url;
    private final CalendarDataReader calendarDataReader;
    private final String logSubjectId;
    private final StringFormattable eventsLoggableUrl;

    /// @param href the calendar's href on the server, which is also its id
    public IcloudCalendar(String href, HttpUrl url, String name, CalendarDataReader calendarDataReader, String logSubjectId) {
        id = checkNotNull(href);
        this.url = checkNotNull(url);
        this.name = checkNotNull(name);
        this.calendarDataReader = checkNotNull(calendarDataReader);
        this.logSubjectId = checkNotNull(logSubjectId);
        redactedName = redact(name);
        redactedId = redact(id);
        eventsLoggableUrl = CalDavClient.createEventsLoggableUrl(logSubjectId, redactedName);
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
        logger.debug("[{}] Calendar {}: fetching events for {}...{}", logSubjectId, redactedName, from, to);
        return calendarDataReader.read(url, eventsLoggableUrl, from, to, this::toCalendarEvents);
    }

    private List<CalendarEvent> toCalendarEvents(List<String> calendarData) {
        var calendarBuilder = new CalendarBuilder();
        var resultBuilder = ImmutableList.<CalendarEvent>builderWithExpectedSize(calendarData.size());
        for (String iCalendar : calendarData) {
            try {
                // Relaxed, because the XML the data arrives in turns each CRLF into a bare LF, so a folded line continues after a bare LF.
                var calendar = calendarBuilder.build(new UnfoldingReader(new StringReader(iCalendar), true));
                for (VEvent event : calendar.<VEvent>getComponents(Component.VEVENT)) {
                    Optional<CalendarEvent> calendarEvent = toCalendarEvent(event);
                    if (calendarEvent.isPresent()) {
                        resultBuilder.add(calendarEvent.get());
                    } else {
                        logger.info("[{}] Calendar {}: skipping an entry with no start, or with neither an end nor a duration", logSubjectId, redactedName);
                    }
                }
            } catch (ParserException e) {
                // The parser's message can quote the data, which holds the user's own words, so the log line keeps only its line number.
                logger.info("[{}] Calendar {}: skipping an entry whose iCalendar data is unreadable at line {}", logSubjectId, redactedName, e.getLineNo());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        List<CalendarEvent> result = resultBuilder.build();
        if (logger.isDebugEnabled()) {
            logger.debug("[{}] Calendar {}: fetched {} events", logSubjectId, redactedName, result.size());
        }
        return result;
    }

    /// @return the event, or empty when `event` has no {@value Property#DTSTART}, or neither a {@value Property#DTEND} nor a
    ///         {@value Property#DURATION}. The event has:
    ///
    ///         - an empty summary when `event` has none
    ///         - an end at its {@value Property#DTSTART} plus its {@value Property#DURATION} when `event` has no {@value Property#DTEND}
    @VisibleForTesting
    static Optional<CalendarEvent> toCalendarEvent(VEvent event) {
        DtStart<Temporal> start = event.getDateTimeStart();
        if (start == null) {
            return Optional.empty();
        }
        Optional<DtEnd<Temporal>> end = event.getEndDate(false);
        if (end.isEmpty()) {
            // getEndDate(true) derives an end from RFC 5545's default duration for an entry that declares no DURATION.
            if (event.getDuration() == null) {
                return Optional.empty();
            }
            end = event.getEndDate(true);
        }
        Summary summaryProperty = event.getSummary();
        String summary = summaryProperty == null ? "" : summaryProperty.getValue();
        Optional<String> location = event.getProperty(Property.LOCATION).map(Content::getValue);
        return Optional.of(CalendarEvent.builder()
                                        .setId(createId(event, summary, location.orElse(null)))
                                        .setStart(start.getDate())
                                        .setEnd(end.orElseThrow().getDate())
                                        .setSummary(summary)
                                        .setDescription(event.getProperty(Property.DESCRIPTION).map(Content::getValue))
                                        .setLocation(location)
                                        .build());
    }

    /// Identifies one occurrence: an expanded instance of a recurring entry carries the series' `UID` and its own `RECURRENCE-ID`, so the two together are
    /// what distinguish it from its siblings.
    ///
    /// @param location the entry's location, or `null` where it declares none, which derives the fallback id from the title alone
    private static String createId(VEvent event, String summary, @Nullable String location) {
        return event.getUid()
                    .map(uid -> event.getProperty(Property.RECURRENCE_ID)
                                     .map(recurrenceId -> uid.getValue() + '/' + recurrenceId.getValue())
                                     .orElseGet(uid::getValue))
                    .orElseGet(() -> createContentDerivedId(summary, location));
    }

    @Override
    public String toString() {
        return toString(64);
    }

    @Override
    public void formatTo(Appendable appendable) {
        Append.to(appendable, "IcloudCalendar{id='");
        Append.to(appendable, redactedId);
        Append.to(appendable, "', name='");
        Append.to(appendable, redactedName);
        Append.to(appendable, "'}");
    }

    public interface CalendarDataReader {
        /// @param <T>         the type of the read's value
        /// @param loggableUrl the form of `calendarUrl` that log lines and failure messages may show
        /// @param parser      turns the iCalendar text of each event overlapping `[from, to)` into the read's value
        /// @return a future completing with the value that `parser` returns for the events overlapping `[from, to)`
        /// @implSpec An implementation backed by a component that stops must return a future that never completes once that component has stopped, even
        ///           for a request that was in flight when it stopped.
        <T> CompletableFuture<T> read(HttpUrl calendarUrl,
                                      StringFormattable loggableUrl,
                                      Instant from,
                                      Instant to,
                                      Function<? super List<String>, ? extends T> parser);
    }
}
