package net.yudichev.jiotty.connector.google.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.CalendarList;
import com.google.api.services.calendar.model.Events;
import com.google.common.annotations.VisibleForTesting;
import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.common.lang.StringFormattable;
import net.yudichev.jiotty.common.rest.HttpResponseException;
import net.yudichev.jiotty.common.rest.RestClients;
import net.yudichev.jiotty.common.rest.UnsuccessfulResponseDetailExtractor;
import net.yudichev.jiotty.common.time.calendar.CalendarAuthorisationException;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static net.yudichev.jiotty.common.rest.BearerTokens.BEARER_PREFIX;

/// The Google Calendar API requests this connector makes. A failure carries only the status and Google's error `reason`, and its loggable URL names the
/// subject in place of the calendar id, which is often the account's email address.
final class GoogleCalendarApi {
    @VisibleForTesting
    static final String SHOW_DELETED_PARAM = "showDeleted";
    @VisibleForTesting
    static final String SYNC_TOKEN_PARAM = "syncToken";
    @VisibleForTesting
    static final String TIME_MIN_PARAM = "timeMin";
    @VisibleForTesting
    static final String TIME_MAX_PARAM = "timeMax";
    @VisibleForTesting
    static final String SINGLE_EVENTS_PARAM = "singleEvents";
    @VisibleForTesting
    static final String ORDER_BY_PARAM = "orderBy";
    @VisibleForTesting
    static final String ORDER_BY_START_TIME = "startTime";
    @VisibleForTesting
    static final String PAGE_TOKEN_PARAM = "pageToken";
    private static final HttpUrl BASE_URL = HttpUrl.get(Calendar.DEFAULT_BASE_URL);
    /// Reads Google's error `reason`, a fixed code such as {@value GoogleCalendarService#RATE_LIMIT_EXCEEDED_REASON} that names no user data, which
    /// [HttpResponseException#body()] then carries.
    private static final UnsuccessfulResponseDetailExtractor ERROR_REASON_EXTRACTOR =
            (_, body) -> Json.tryParse(body, JsonNode.class)
                             .map(root -> root.path("error").path("errors").path(0).path("reason"))
                             .filter(JsonNode::isTextual)
                             .map(JsonNode::asText);

    private final OkHttpClient httpClient;
    private final String logSubjectId;
    private final StringFormattable calendarListLoggableUrl;

    public GoogleCalendarApi(OkHttpClient httpClient, String logSubjectId) {
        this.httpClient = checkNotNull(httpClient);
        this.logSubjectId = checkNotNull(logSubjectId);
        calendarListLoggableUrl = createLoggableUrl("Google calendar list");
    }

    /// Fetches the calendar list — all of it, or what changed since `syncToken` — handing each page to `pageConsumer`.
    ///
    /// @param syncToken the token of the sync to continue from, or empty for a full listing
    /// @return the last page, which carries the token for the next incremental sync
    public CompletableFuture<CalendarList> listCalendars(String accessToken, Optional<String> syncToken, Consumer<? super CalendarList> pageConsumer) {
        HttpUrl.Builder urlBuilder = BASE_URL.newBuilder()
                                             .addPathSegments("users/me/calendarList")
                                             .addQueryParameter(SHOW_DELETED_PARAM, "true");
        syncToken.ifPresent(token -> urlBuilder.addQueryParameter(SYNC_TOKEN_PARAM, token));
        return forEachPage(accessToken, urlBuilder.build(), CalendarList.class, calendarListLoggableUrl, pageConsumer, CalendarList::getNextPageToken);
    }

    public StringFormattable createEventsLoggableUrl(String redactedCalendarName) {
        return createLoggableUrl("Google events of calendar " + redactedCalendarName);
    }

    /// @param description what the request reads; must not hold personal data
    /// @return a loggable URL rendering as `[logSubjectId] description`
    private StringFormattable createLoggableUrl(String description) {
        return appendable -> {
            Append.to(appendable, '[');
            Append.to(appendable, logSubjectId);
            Append.to(appendable, "] ");
            Append.to(appendable, description);
        };
    }

    /// Fetches every occurrence of every event overlapping `[from, to)` in start order, handing each page to `pageConsumer`.
    ///
    /// @param loggableUrl names the calendar in log lines and failures, from [#createEventsLoggableUrl]
    public CompletableFuture<Events> listEvents(String accessToken,
                                                String calendarId,
                                                StringFormattable loggableUrl,
                                                Instant from,
                                                Instant to,
                                                Consumer<? super Events> pageConsumer) {
        HttpUrl url = BASE_URL.newBuilder()
                              .addPathSegment("calendars")
                              .addPathSegment(calendarId)
                              .addPathSegment("events")
                              .addQueryParameter(TIME_MIN_PARAM, from.toString())
                              .addQueryParameter(TIME_MAX_PARAM, to.toString())
                              .addQueryParameter(SINGLE_EVENTS_PARAM, "true")
                              .addQueryParameter(ORDER_BY_PARAM, ORDER_BY_START_TIME)
                              .build();
        return forEachPage(accessToken, url, Events.class, loggableUrl, pageConsumer, Events::getNextPageToken);
    }

    /// @param <PageT>               the type each page's body deserialises to
    /// @param nextPageTokenAccessor the token of the page after the given one, or `null` on the last page
    private <PageT> CompletableFuture<PageT> forEachPage(String accessToken,
                                                         HttpUrl firstUrl,
                                                         Class<PageT> pageType,
                                                         StringFormattable loggableUrl,
                                                         Consumer<? super PageT> pageConsumer,
                                                         Function<PageT, @Nullable String> nextPageTokenAccessor) {
        String authorisation = BEARER_PREFIX + accessToken;
        return RestClients.forEachPage(
                url -> RestClients.callSuppressingResponseBody(httpClient.newCall(new Request.Builder().url(url)
                                                                                                       .header(AUTHORIZATION, authorisation)
                                                                                                       .get()
                                                                                                       .build()),
                                                               body -> getAsUnchecked(() -> GsonFactory.getDefaultInstance().fromString(body, pageType)),
                                                               // the callers retry the whole listing, which a retried page would only duplicate
                                                               0,
                                                               loggableUrl,
                                                               ERROR_REASON_EXTRACTOR),
                firstUrl,
                pageConsumer,
                page -> Optional.ofNullable(nextPageTokenAccessor.apply(page))
                                .map(token -> firstUrl.newBuilder().setQueryParameter(PAGE_TOKEN_PARAM, token).build()));
    }

    /// Passes the access token Google rejected with `httpFailure` to `credentialRejectionHandler`, together with the rejection's description.
    ///
    /// @param requestDescription what the rejected request does, completing "trying to …"
    /// @return the failure to complete the rejected request's future with
    public static CalendarAuthorisationException reportCredentialRejection(HttpResponseException httpFailure,
                                                                           String requestDescription,
                                                                           String rejectedAccessToken,
                                                                           BiConsumer<String, String> credentialRejectionHandler) {
        String rejection = "Google Calendar API rejected the credential (" + describe(httpFailure) + ") trying to " + requestDescription;
        credentialRejectionHandler.accept(rejectedAccessToken, rejection);
        return new CalendarAuthorisationException(rejection, httpFailure);
    }

    /// @param requestDescription what the failed request does, completing "Failed to …"
    /// @return the failure to complete the failed request's future with, for a response that leaves the credential valid
    public static RuntimeException createResponseFailure(HttpResponseException httpFailure, String requestDescription) {
        return new RuntimeException("Failed to " + requestDescription + ": " + describe(httpFailure), httpFailure);
    }

    /// Describes Google's response to a request of this API by its status and error `reason` alone, as
    /// `HTTP 403` followed by {@value GoogleCalendar#INSUFFICIENT_PERMISSIONS_REASON}.
    private static String describe(HttpResponseException httpFailure) {
        String status = "HTTP " + httpFailure.statusCode();
        return errorReason(httpFailure).map(reason -> status + ' ' + reason).orElse(status);
    }

    /// @return the error `reason` Google's response to a request of this API listed, or empty when it listed none
    public static Optional<String> errorReason(HttpResponseException httpFailure) {
        return Optional.of(httpFailure.body()).filter(body -> !body.equals(RestClients.WITHHELD_BODY));
    }
}
