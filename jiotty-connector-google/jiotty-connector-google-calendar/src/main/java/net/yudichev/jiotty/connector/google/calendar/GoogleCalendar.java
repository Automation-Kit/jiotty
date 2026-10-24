package net.yudichev.jiotty.connector.google.calendar;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.Events;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import net.yudichev.jiotty.common.async.TaskExecutor;
import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.StringFormattable;
import net.yudichev.jiotty.common.rest.HttpResponseException;
import net.yudichev.jiotty.common.time.calendar.Calendar;
import net.yudichev.jiotty.common.time.calendar.CalendarEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static net.yudichev.jiotty.common.rest.HttpStatuses.FORBIDDEN_403;
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;
import static net.yudichev.jiotty.common.security.LogRedaction.redact;
import static net.yudichev.jiotty.common.time.calendar.CalendarEventIds.createContentDerivedId;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarApi.createResponseFailure;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarApi.errorReason;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarApi.reportCredentialRejection;

final class GoogleCalendar implements Calendar, StringFormattable {
    /// The reason of the forbidden response to a request whose grant lacks a scope the request needs, as when the user unticks one of the scopes on the
    /// consent screen.
    @VisibleForTesting
    static final String INSUFFICIENT_PERMISSIONS_REASON = "insufficientPermissions";
    /// What [#fetchEvents] does, followed by the calendar's redacted title.
    @VisibleForTesting
    static final String EVENTS_FETCH = "fetch events for calendar ";

    private static final Logger logger = LogManager.getLogger(GoogleCalendar.class);

    private final GoogleCalendarApi api;
    private final TaskExecutor ownerExecutor;
    private final Supplier<CompletableFuture<String>> accessTokenSupplier;
    private final BooleanSupplier ownerStartedCheck;
    private final BiConsumer<String, String> credentialRejectionHandler;
    private final String id;
    private final String name;
    private final String logSubjectId;
    /// Redacted form of [#name], computed once: `name` is a calendar title (PII) that would otherwise be re-redacted on every log line.
    private final String redactedName;
    /// Redacted form of [#id], computed once for the same reason: a Google calendar id is the account's email address.
    private final String redactedId;
    private final StringFormattable eventsLoggableUrl;
    /// What [#fetchEvents] does, completing its failure messages.
    private final String eventsFetchDescription;

    /// @param ownerExecutor              the owning service's executor, on which each request's outcome is read
    /// @param accessTokenSupplier        supplies the token every request carries; once the owning service has stopped, must return a future that never
    ///                                   completes
    /// @param ownerStartedCheck          whether the owning service is still started, read on `ownerExecutor` as each request's outcome is
    /// @param credentialRejectionHandler invoked on `ownerExecutor` with the access token Google rejected and the rejection's description
    public GoogleCalendar(GoogleCalendarApi api,
                          TaskExecutor ownerExecutor,
                          Supplier<CompletableFuture<String>> accessTokenSupplier,
                          BooleanSupplier ownerStartedCheck,
                          BiConsumer<String, String> credentialRejectionHandler,
                          String id,
                          String name,
                          String logSubjectId) {
        this.api = checkNotNull(api);
        this.ownerExecutor = checkNotNull(ownerExecutor);
        this.accessTokenSupplier = checkNotNull(accessTokenSupplier);
        this.ownerStartedCheck = checkNotNull(ownerStartedCheck);
        this.credentialRejectionHandler = checkNotNull(credentialRejectionHandler);
        this.id = checkNotNull(id);
        this.name = checkNotNull(name);
        this.logSubjectId = checkNotNull(logSubjectId);
        redactedName = redact(name);
        redactedId = redact(id);
        eventsLoggableUrl = api.createEventsLoggableUrl(redactedName);
        eventsFetchDescription = EVENTS_FETCH + redactedName;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    /// Never completes once the owning service has stopped, even for a request that was already in flight when it stopped. A token Google rejects is passed
    /// to [#credentialRejectionHandler].
    @Override
    public CompletableFuture<List<CalendarEvent>> fetchEvents(Instant from, Instant to) {
        return accessTokenSupplier.get().thenCompose(accessToken -> {
            logger.debug("[{}] Calendar {}: fetching events for {}...{}", logSubjectId, redactedName, from, to);
            // Collected off the owner's executor and converted on it once the last page has arrived, sized for the events of a typical window.
            List<Event> items = new ArrayList<>(64);
            CompletableFuture<Events> listing = api.listEvents(accessToken, id, eventsLoggableUrl, from, to, page -> {
                List<Event> pageItems = page.getItems();
                if (pageItems != null) {
                    items.addAll(pageItems);
                }
            });
            return ownerExecutor.handleWhile(ownerStartedCheck,
                                             listing,
                                             "Reading the Google calendar events",
                                             (_, failure) -> failure == null
                                                             ? completedFuture(toCalendarEvents(items))
                                                             : failedFuture(toFetchFailure(accessToken, failure)));
        });
    }

    private List<CalendarEvent> toCalendarEvents(List<Event> items) {
        var resultBuilder = ImmutableList.<CalendarEvent>builderWithExpectedSize(items.size());
        for (Event event : items) {
            toCalendarEvent(event).ifPresent(resultBuilder::add);
        }
        List<CalendarEvent> result = resultBuilder.build();
        if (logger.isDebugEnabled()) {
            logger.debug("[{}] Calendar {}: fetched {} events", logSubjectId, redactedName, result.size());
        }
        return result;
    }

    /// Converts one item of a {@value GoogleCalendarApi#SINGLE_EVENTS_PARAM} listing, which is a single occurrence of its entry.
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

    /// @param accessToken the token the failed request carried
    private Throwable toFetchFailure(String accessToken, Throwable failure) {
        Optional<HttpResponseException> httpFailureInChain = HttpResponseException.findIn(failure);
        if (httpFailureInChain.isEmpty()) {
            return failure;
        }
        HttpResponseException httpFailure = httpFailureInChain.get();
        // An unauthorised response, or a forbidden one for a missing scope, rejects the credential every calendar is read with; any other rejected request
        // fails this calendar alone and leaves the credential valid.
        if (httpFailure.statusCode() == UNAUTHORIZED_401
            || (httpFailure.statusCode() == FORBIDDEN_403 && errorReason(httpFailure).filter(INSUFFICIENT_PERMISSIONS_REASON::equals).isPresent())) {
            return reportCredentialRejection(httpFailure, eventsFetchDescription, accessToken, credentialRejectionHandler);
        }
        return createResponseFailure(httpFailure, eventsFetchDescription);
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
