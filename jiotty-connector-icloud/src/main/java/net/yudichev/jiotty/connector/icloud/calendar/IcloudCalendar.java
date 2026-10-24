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
import net.yudichev.jiotty.common.async.TaskExecutor;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
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
    private final Supplier<CompletableFuture<CalDavClient>> calDavClientSupplier;
    private final TaskExecutor ownerExecutor;
    private final BooleanSupplier ownerStartedCheck;
    private final String logSubjectId;
    private final StringFormattable eventsLoggableUrl;

    /// @param href                 the href the server names the calendar by, which serves as its id
    /// @param calDavClientSupplier supplies the client to read events through; once the owning service has stopped, must return a future that never
    ///                             completes
    /// @param ownerExecutor        the owning service's executor, on which each request's outcome is read
    /// @param ownerStartedCheck    whether the owning service is still started, read on `ownerExecutor` as each request's outcome is
    public IcloudCalendar(String href,
                          HttpUrl url,
                          String name,
                          Supplier<CompletableFuture<CalDavClient>> calDavClientSupplier,
                          TaskExecutor ownerExecutor,
                          BooleanSupplier ownerStartedCheck,
                          String logSubjectId) {
        id = checkNotNull(href);
        this.url = checkNotNull(url);
        this.name = checkNotNull(name);
        this.calDavClientSupplier = checkNotNull(calDavClientSupplier);
        this.ownerExecutor = checkNotNull(ownerExecutor);
        this.ownerStartedCheck = checkNotNull(ownerStartedCheck);
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

    /// Never completes once the owning service has stopped, even for a request that was already in flight when it stopped.
    @Override
    public CompletableFuture<List<CalendarEvent>> fetchEvents(Instant from, Instant to) {
        logger.debug("[{}] Calendar {}: fetching events for {}...{}", logSubjectId, redactedName, from, to);
        return calDavClientSupplier.get().thenCompose(client -> ownerExecutor.handleWhile(
                ownerStartedCheck,
                client.fetchCalendarData(url, eventsLoggableUrl, from, to),
                "Reading the iCloud calendar events",
                (calendarData, failure) -> failure == null ? completedFuture(toCalendarEvents(calendarData)) : failedFuture(failure)));
    }

    private List<CalendarEvent> toCalendarEvents(List<String> calendarData) {
        var calendarBuilder = new CalendarBuilder();
        var resultBuilder = ImmutableList.<CalendarEvent>builderWithExpectedSize(calendarData.size());
        for (String iCalendar : calendarData) {
            try {
                // Relaxed, because the XML the data arrives in turns each CRLF into a bare LF, so a folded line continues after a bare LF.
                var calendar = calendarBuilder.build(new UnfoldingReader(new StringReader(iCalendar), true));
                for (VEvent event : calendar.<VEvent>getComponents(Component.VEVENT)) {
                    resultBuilder.add(toCalendarEvent(event));
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

    @VisibleForTesting
    static CalendarEvent toCalendarEvent(VEvent event) {
        String summary = event.getSummary().getValue();
        Optional<String> location = event.getProperty(Property.LOCATION).map(Content::getValue);
        return CalendarEvent.builder()
                            .setId(createId(event, summary, location.orElse(null)))
                            .setStart(event.getDateTimeStart().getDate())
                            .setEnd(event.getDateTimeEnd().getDate())
                            .setSummary(summary)
                            .setDescription(event.getProperty(Property.DESCRIPTION).map(Content::getValue))
                            .setLocation(location)
                            .build();
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
}
