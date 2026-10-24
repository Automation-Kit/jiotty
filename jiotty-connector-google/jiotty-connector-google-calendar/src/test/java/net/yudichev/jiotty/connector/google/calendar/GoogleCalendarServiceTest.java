package net.yudichev.jiotty.connector.google.calendar;

import net.yudichev.jiotty.common.async.ListenerBackedTaskExceptionHandlerRegistry;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.rest.HttpResponseException;
import net.yudichev.jiotty.common.rest.HttpStatuses;
import net.yudichev.jiotty.common.rest.OkHttpStubs.PendingCall;
import net.yudichev.jiotty.common.rest.RestClients;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.common.testutil.JiottyPiiLogGuardExtension;
import net.yudichev.jiotty.common.testutil.LogCapture;
import net.yudichev.jiotty.common.time.calendar.Calendar;
import net.yudichev.jiotty.common.time.calendar.CalendarAuthorisationException;
import net.yudichev.jiotty.common.time.calendar.CalendarEvent;
import net.yudichev.jiotty.common.time.calendar.CalendarService.CalendarsResult;
import net.yudichev.jiotty.common.time.calendar.CalendarService.CalendarsResult.Calendars;
import net.yudichev.jiotty.common.time.calendar.CalendarService.CalendarsResult.NotYetAuthenticated;
import net.yudichev.jiotty.security.OAuth2TokenManager;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.logging.log4j.Level;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.google.common.base.Throwables.getStackTraceAsString;
import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static net.yudichev.jiotty.common.rest.BearerTokens.BEARER_PREFIX;
import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_GATEWAY_502;
import static net.yudichev.jiotty.common.rest.HttpStatuses.FORBIDDEN_403;
import static net.yudichev.jiotty.common.rest.HttpStatuses.GONE_410;
import static net.yudichev.jiotty.common.rest.HttpStatuses.INTERNAL_SERVER_ERROR_500;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NOT_FOUND_404;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;
import static net.yudichev.jiotty.common.rest.OkHttpClientAssertions.assertGivesUpAfter;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.response;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.stubCalls;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.stubDeferredCalls;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.takeOnlyPendingCall;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendar.EVENTS_FETCH;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendar.INSUFFICIENT_PERMISSIONS_REASON;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarApi.PAGE_TOKEN_PARAM;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarApi.SYNC_TOKEN_PARAM;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarApi.TIME_MAX_PARAM;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarApi.TIME_MIN_PARAM;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarService.CALENDAR_LIST_SYNC;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarService.RATE_LIMIT_EXCEEDED_REASON;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarService.USER_RATE_LIMIT_EXCEEDED_REASON;
import static net.yudichev.jiotty.connector.google.calendar.GoogleErrorReasons.AUTH_ERROR_REASON;
import static net.yudichev.jiotty.connector.google.calendar.GoogleErrorReasons.FORBIDDEN_FOR_NON_ORGANIZER_REASON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoogleCalendarServiceTest {
    private static final String ACCESS_TOKEN = "access-token";
    private static final String SUBJECT = "test-user";
    private static final String REDIRECT_URI = "the-redirect";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    /// A Google calendar id is the account's email address, which [JiottyPiiLogGuardExtension] recognises, so a log line naming a calendar fails the test.
    private static final String HOME_ID = "alice@example.com";
    /// A second calendar id of the same account, an email address so that [JiottyPiiLogGuardExtension] catches it in a log line.
    private static final String WORK_ID = "alice.work@example.com";
    /// The id of the one calendar the events tests read, pseudonymous because production logs the failures it handles.
    private static final String EVENTS_CALENDAR_ID = "calendar-7f3a9c";
    private static final String HOME_CALENDAR_LIST_JSON = """
                                                          {"items":[{"id":"%s","summary":"Home"}],"nextSyncToken":"sync-1"}""".formatted(HOME_ID);
    /// Lists [#EVENTS_CALENDAR_ID], titled "Home".
    private static final String EVENTS_CALENDAR_LIST_JSON = """
                                                            {"items":[{"id":"%s","summary":"Home"}],"nextSyncToken":"sync-1"}""".formatted(EVENTS_CALENDAR_ID);
    private static final Instant FROM = Instant.parse("2026-06-20T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-22T00:00:00Z");
    private final ProgrammableClock clock = new ProgrammableClock();
    private final SchedulingExecutor executor = clock.createSingleThreadedSchedulingExecutor("test");
    @Mock
    private OkHttpClient httpClient;
    private FakeOAuth2TokenManager tokenManager;
    private GoogleCalendarService service;
    /// Every request the service issued, in order, once [#startService(Function)] has stubbed the client.
    private List<Request> issuedRequests = List.of();

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.stop();
        }
    }

    @Test
    void retrieveCalendars_fullSync_returnsAllCalendarsAndPrefersSummaryOverride() {
        startService(request -> ok(request, """
                                            {"items":[
                                              {"id":"%s","summary":"Home"},
                                              {"id":"%s","summary":"Work","summaryOverride":"Work (mine)"}
                                            ],"nextSyncToken":"sync-1"}""".formatted(HOME_ID, WORK_ID)));

        assertThat(retrieveCalendars())
                .extracting(Calendar::id, Calendar::name)
                .containsExactly(tuple(HOME_ID, "Home"), tuple(WORK_ID, "Work (mine)"));
    }

    @Test
    void retrieveCalendars_fullSync_paginates() {
        startService(request -> hasPageToken(request, "page-2")
                                ? ok(request, """
                                              {"items":[{"id":"%s","summary":"Work"}],"nextSyncToken":"sync-1"}""".formatted(WORK_ID))
                                : ok(request, """
                                              {"items":[{"id":"%s","summary":"Home"}],"nextPageToken":"page-2"}""".formatted(HOME_ID)));

        assertThat(retrieveCalendars()).extracting(Calendar::name).containsExactly("Home", "Work");
    }

    @Test
    void retrieveCalendars_incrementalSync_appliesUpdatesAndRemovals() {
        startService(request -> hasSyncToken(request, "sync-1")
                                ? ok(request, """
                                              {"items":[{"id":"%s","summary":"Home renamed"},{"id":"%s","deleted":true}],"nextSyncToken":"sync-2"}"""
                                              .formatted(HOME_ID, WORK_ID))
                                : ok(request, """
                                              {"items":[{"id":"%s","summary":"Home"},{"id":"%s","summary":"Work"}],"nextSyncToken":"sync-1"}"""
                                              .formatted(HOME_ID, WORK_ID)));

        assertThat(retrieveCalendars()).extracting(Calendar::name).containsExactly("Home", "Work");
        // second refresh sends sync-1, gets a delta: the home calendar renamed, the work one removed
        assertThat(retrieveCalendars())
                .extracting(Calendar::id, Calendar::name)
                .containsExactly(tuple(HOME_ID, "Home renamed"));
    }

    /// A full listing leaves out the calendars the user hid, so an incremental one reporting a calendar as hidden removes it, keeping the two alike.
    @Test
    void retrieveCalendars_incrementalSync_dropsACalendarTheUserHid() {
        startService(request -> hasSyncToken(request, "sync-1")
                                ? ok(request, """
                                              {"items":[{"id":"%s","summary":"Work","hidden":true}],"nextSyncToken":"sync-2"}""".formatted(WORK_ID))
                                : ok(request, """
                                              {"items":[{"id":"%s","summary":"Home"},{"id":"%s","summary":"Work"}],"nextSyncToken":"sync-1"}"""
                                              .formatted(HOME_ID, WORK_ID)));

        assertThat(retrieveCalendars()).extracting(Calendar::name).containsExactly("Home", "Work");
        assertThat(retrieveCalendars()).extracting(Calendar::name).containsExactly("Home");
    }

    @Test
    void retrieveCalendars_syncTokenExpired_fullResync() {
        startService(request -> hasSyncToken(request, "sync-1")
                                ? gone(request)
                                : createHomeCalendarListResponse(request));

        assertThat(retrieveCalendars()).extracting(Calendar::name).containsExactly("Home");
        // the incremental refresh sends sync-1 and gets 410; the service drops the token and full-resyncs, recovering the list
        assertThat(retrieveCalendars()).extracting(Calendar::name).containsExactly("Home");
    }

    /// A listing without a sync token leaves nothing to sync from, so the next refresh lists everything again.
    @Test
    void retrieveCalendars_listingWithoutASyncToken_listsInFullNextTime() {
        startService(request -> ok(request, "{}"));

        assertThat(retrieveCalendars()).isEmpty();
        assertThat(retrieveCalendars()).isEmpty();
        assertThat(issuedRequests).hasSize(2).allSatisfy(request -> assertThat(request.url().queryParameter(SYNC_TOKEN_PARAM)).isNull());
    }

    /// A {@value HttpStatuses#GONE_410} means the sync token expired only when one was sent; to a full listing it is a failure like any other.
    @Test
    void retrieveCalendars_goneWithoutASyncToken_fails() {
        startService(GoogleCalendarServiceTest::gone);

        CompletableFuture<CalendarsResult> future = service.retrieveCalendars();
        clock.tick();

        assertThat(future).failsWithin(Duration.ZERO).withThrowableThat().withMessageContaining("Failed to " + CALENDAR_LIST_SYNC);
    }

    /// A full resync is tried once: a second {@value HttpStatuses#GONE_410} fails the sync.
    @Test
    void retrieveCalendars_goneAgainAfterAFullResync_fails() {
        startService(request -> issuedRequests.size() == 1 ? createHomeCalendarListResponse(request) : gone(request));
        assertThat(retrieveCalendars()).hasSize(1);

        CompletableFuture<CalendarsResult> future = service.retrieveCalendars();
        clock.tick();

        assertThat(future).failsWithin(Duration.ZERO).withThrowableThat().withMessageContaining("Failed to " + CALENDAR_LIST_SYNC);
        assertThat(issuedRequests).hasSize(3);
    }

    @Test
    void fetchEvents_pageWithoutItems_returnsNoEvents() {
        startServiceWithEventsAnswering(request -> ok(request, "{}"));

        assertThat(fetchEvents(retrieveCalendars().getFirst())).isEmpty();
    }

    /// A refresh asked for while another is in flight must see the outcome of a sync that began after it was asked for — the one in flight may already have
    /// missed the change that prompted the request.
    @Test
    void retrieveCalendars_whileASyncIsInFlight_syncsAgainOnceItFinishes() {
        List<PendingCall> pendingCalls = startServiceWithDeferredCalls();

        CompletableFuture<CalendarsResult> firstSync = service.retrieveCalendars();
        clock.tick();
        CompletableFuture<CalendarsResult> secondSync = service.retrieveCalendars();
        clock.tick();
        PendingCall firstCall = takeOnlyPendingCall(pendingCalls);
        assertThat(pendingCalls).as("the second sync waits for the first").isEmpty();

        firstCall.respond(OK_200, HOME_CALENDAR_LIST_JSON);
        clock.tick();
        assertThat(firstSync).succeedsWithin(Duration.ZERO);
        PendingCall secondCall = takeOnlyPendingCall(pendingCalls);
        assertThat(secondCall.request().url().queryParameter(SYNC_TOKEN_PARAM)).isEqualTo("sync-1");
        secondCall.respond(OK_200, """
                                   {"items":[{"id":"%s","deleted":true}],"nextSyncToken":"sync-2"}""".formatted(HOME_ID));
        clock.tick();

        assertThat(secondSync).succeedsWithin(Duration.ZERO)
                              .isInstanceOfSatisfying(Calendars.class, calendars -> assertThat(calendars.calendars()).isEmpty());
    }

    @Test
    void fetchEvents_mapsTimedAndAllDayEvents() {
        startServiceWithEventsAnswering(request -> ok(request, """
                                                               {"items":[
                                                                 {"id":"e1","summary":"Meeting","description":"Sync up","location":"Room 5",
                                                                  "start":{"dateTime":"2026-06-20T10:00:00Z"},"end":{"dateTime":"2026-06-20T11:00:00Z"}},
                                                                 {"id":"e2","summary":"Holiday","start":{"date":"2026-06-21"},"end":{"date":"2026-06-22"}}
                                                               ]}"""));

        Calendar calendar = retrieveCalendars().getFirst();

        assertThat(fetchEvents(calendar)).satisfiesExactly(
                timed -> {
                    assertThat(timed.start()).isEqualTo(Instant.parse("2026-06-20T10:00:00Z"));
                    assertThat(timed.end()).isEqualTo(Instant.parse("2026-06-20T11:00:00Z"));
                    assertThat(timed.summary()).isEqualTo("Meeting");
                    assertThat(timed.description()).hasValue("Sync up");
                    assertThat(timed.location()).hasValue("Room 5");
                },
                allDay -> {
                    assertThat(allDay.start()).isEqualTo(LocalDate.parse("2026-06-21"));
                    assertThat(allDay.end()).isEqualTo(LocalDate.parse("2026-06-22"));
                    assertThat(allDay.summary()).isEqualTo("Holiday");
                    assertThat(allDay.description()).isEmpty();
                    assertThat(allDay.location()).isEmpty();
                });
    }

    @Test
    void fetchEvents_paginates() {
        startServiceWithEventsAnswering(request -> hasPageToken(request, "evt-2")
                                                   ? ok(request, """
                                                                 {"items":[{"id":"e2","summary":"Second",
                                                                   "start":{"dateTime":"2026-06-20T12:00:00Z"},"end":{"dateTime":"2026-06-20T13:00:00Z"}}]}""")
                                                   : ok(request, """
                                                                 {"items":[{"id":"e1","summary":"First",
                                                                   "start":{"dateTime":"2026-06-20T10:00:00Z"},"end":{"dateTime":"2026-06-20T11:00:00Z"}}],
                                                                  "nextPageToken":"evt-2"}"""));

        assertThat(fetchEvents(retrieveCalendars().getFirst()))
                .extracting(CalendarEvent::summary)
                .containsExactly("First", "Second");
    }

    /// A calendar id may hold characters with a meaning in a URL, as the id of a public holiday calendar does.
    @Test
    void fetchEvents_requestsTheCalendarByItsIdAsOnePathSegment() {
        String holidaysId = "en.uk#holiday@group.v.calendar.google.com";
        startService(request -> isEventsRequest(request, holidaysId)
                                ? ok(request, """
                                              {"items":[]}""")
                                : ok(request, """
                                              {"items":[{"id":"%s","summary":"Holidays in United Kingdom"}],"nextSyncToken":"sync-1"}"""
                                              .formatted(holidaysId)));

        assertThat(fetchEvents(retrieveCalendars().getFirst())).isEmpty();
        assertThat(issuedRequests).last().satisfies(request -> {
            assertThat(request.url().encodedPath()).isEqualTo("/calendar/v3/calendars/en.uk%23holiday@group.v.calendar.google.com/events");
            assertThat(request.url().queryParameter(TIME_MIN_PARAM)).isEqualTo(FROM.toString());
            assertThat(request.url().queryParameter(TIME_MAX_PARAM)).isEqualTo(TO.toString());
            assertThat(request.header(AUTHORIZATION)).isEqualTo(BEARER_PREFIX + ACCESS_TOKEN);
        });
    }

    /// The failure an operator reads names whose calendar failed, but never its id — the account's email address.
    @Test
    void fetchEvents_transportFailure_namesTheSubjectAndTheRedactedCalendar() {
        List<PendingCall> pendingCalls = startServiceWithDeferredCalls();
        Calendar calendar = retrieveEventsCalendar(pendingCalls);

        CompletableFuture<List<CalendarEvent>> events = calendar.fetchEvents(FROM, TO);
        clock.tick();
        takeOnlyPendingCall(pendingCalls).fail(new IOException("network down"));
        clock.tick();

        assertThat(events).failsWithin(Duration.ZERO)
                          .withThrowableThat()
                          .withMessageContaining("[" + SUBJECT + "] Google events of calendar Hom")
                          .withMessageNotContaining(EVENTS_CALENDAR_ID)
                          .withRootCauseInstanceOf(IOException.class);
    }

    @Test
    void fetchEvents_afterTheServiceStopped_neverCompletes() {
        startService(GoogleCalendarServiceTest::createHomeCalendarListResponse);
        Calendar calendar = retrieveCalendars().getFirst();
        int requestsBeforeStop = issuedRequests.size();
        service.stop();

        CompletableFuture<List<CalendarEvent>> events = calendar.fetchEvents(FROM, TO);
        clock.tick();

        assertThat(events).isNotDone();
        assertThat(issuedRequests).hasSize(requestsBeforeStop);
    }

    @Test
    void retrieveCalendars_queuedWhenTheServiceStops_neverCompletes() {
        startService(GoogleCalendarServiceTest::createHomeCalendarListResponse);

        CompletableFuture<CalendarsResult> future = service.retrieveCalendars();
        service.stop();
        clock.tick();

        assertThat(future).isNotDone();
        assertThat(issuedRequests).isEmpty();
    }

    @Test
    void fetchEvents_queuedWhenTheServiceStops_neverCompletes() {
        startService(GoogleCalendarServiceTest::createHomeCalendarListResponse);
        Calendar calendar = retrieveCalendars().getFirst();
        int requestsBeforeStop = issuedRequests.size();

        CompletableFuture<List<CalendarEvent>> events = calendar.fetchEvents(FROM, TO);
        service.stop();
        clock.tick();

        assertThat(events).isNotDone();
        assertThat(issuedRequests).hasSize(requestsBeforeStop);
    }

    @Test
    void retrieveCalendars_afterTheServiceStopped_neverCompletes() {
        startService(GoogleCalendarServiceTest::createHomeCalendarListResponse);
        service.stop();

        CompletableFuture<CalendarsResult> future = service.retrieveCalendars();
        clock.tick();

        assertThat(future).isNotDone();
        assertThat(issuedRequests).isEmpty();
    }

    /// Stopping fails the requests in flight, and that failure reaches no caller.
    @Test
    void retrieveCalendars_inFlightWhenTheServiceStops_neverCompletes() {
        List<PendingCall> pendingCalls = startServiceWithDeferredCalls();
        CompletableFuture<CalendarsResult> future = service.retrieveCalendars();
        clock.tick();
        PendingCall listingCall = takeOnlyPendingCall(pendingCalls);

        service.stop();
        listingCall.fail(new IOException("Canceled"));
        clock.tick();

        assertThat(future).isNotDone();
    }

    /// Stopping fails the requests in flight, and that failure reaches no caller.
    @Test
    void fetchEvents_inFlightWhenTheServiceStops_neverCompletes() {
        List<PendingCall> pendingCalls = startServiceWithDeferredCalls();
        CompletableFuture<List<CalendarEvent>> events = retrieveEventsCalendar(pendingCalls).fetchEvents(FROM, TO);
        clock.tick();
        PendingCall eventsCall = takeOnlyPendingCall(pendingCalls);

        service.stop();
        eventsCall.fail(new IOException("Canceled"));
        clock.tick();

        assertThat(events).isNotDone();
    }

    @Test
    void stop_shutsTheHttpClientDown() {
        startService(GoogleCalendarServiceTest::createHomeCalendarListResponse);
        assertThat(httpClient.dispatcher().executorService().isShutdown()).isFalse();

        service.stop();

        assertThat(httpClient.dispatcher().executorService().isShutdown()).isTrue();
    }

    /// Event titles, descriptions and locations are the user's own words; neither a request nor a response puts them in the log.
    @Test
    void fetchEvents_logsNoEventContent() {
        startServiceWithEventsAnswering(request -> ok(request, """
                                                               {"items":[{"id":"e1","summary":"Dentist","location":"12 Harley Street",
                                                                 "start":{"dateTime":"2026-06-20T10:00:00Z"},"end":{"dateTime":"2026-06-20T11:00:00Z"}}]}"""));
        Calendar calendar = retrieveCalendars().getFirst();

        List<LogCapture.CapturedLine> lines;
        try (var restClientsLog = LogCapture.capture(RestClients.class, Level.DEBUG);
             var calendarLog = LogCapture.capture(GoogleCalendar.class, Level.DEBUG)) {
            assertThat(fetchEvents(calendar)).hasSize(1);
            lines = Stream.concat(restClientsLog.lines().stream(), calendarLog.lines().stream()).toList();
        }

        assertThat(lines).isNotEmpty()
                         .flatMap(line -> line.text().toList())
                         .noneMatch(text -> text.contains("Dentist") || text.contains("Harley Street"));
    }

    @Test
    void subscribeToAuthState_hidesAccessTokenAndForwardsFailure() {
        startService(request -> ok(request, """
                                            {"items":[],"nextSyncToken":"sync-1"}"""));

        var captured = new ArrayList<AuthState>();
        service.subscribeToAuthState(captured::add);
        clock.tick();
        // the live token is ACCESS_TOKEN; subscribers must never see it — only the opaque authenticated marker
        assertThat(captured).last().isInstanceOfSatisfying(AuthState.Success.class,
                                                           success -> assertThat(success.authInfo()).isNotEqualTo(ACCESS_TOKEN));

        tokenManager.setState(new AuthState.PermanentFailure("revoked"));
        clock.tick();
        assertThat(captured).last().isInstanceOfSatisfying(AuthState.PermanentFailure.class,
                                                           failure -> assertThat(failure.description()).isEqualTo("revoked"));
    }

    @Test
    void start_withFreshAuthCode_exchangesItViaTokenManager(@Mock OAuth2TokenManager mockTokenManager) {
        when(mockTokenManager.subscribeToAccessTokenState(any())).thenReturn(Closeable.noop());
        service = createService(mockTokenManager, Optional.of("the-code"), Optional.of("the-verifier"), TIMEOUT, httpClient);
        service.start();

        verify(mockTokenManager).onNewAuthCode("the-code", REDIRECT_URI, Optional.of("the-verifier"));
    }

    @Test
    void disconnect_revokesTheCredential() {
        startService(request -> ok(request, """
                                            {"items":[]}"""));

        assertThat(service.disconnect()).succeedsWithin(Duration.ZERO);
        assertThat(tokenManager.revocationCount()).isEqualTo(1);
    }

    @Test
    void retrieveCalendars_apiIoException_failsFuture() {
        List<PendingCall> pendingCalls = startServiceWithDeferredCalls();

        CompletableFuture<CalendarsResult> future = service.retrieveCalendars();
        clock.tick();
        takeOnlyPendingCall(pendingCalls).fail(new IOException("connection reset"));
        clock.tick();

        assertThat(future).failsWithin(Duration.ZERO)
                          .withThrowableThat()
                          .withMessageContaining("Failed to " + CALENDAR_LIST_SYNC)
                          .withRootCauseInstanceOf(IOException.class);
    }

    /// A server error and a rate limit are transient, so the failure leaves the credential valid. Its status survives as an [HttpResponseException], which is
    /// what classifies an upstream failure as a shared outage rather than a per-user one.
    ///
    /// @param reason the error reason the response lists, or `null` for a response listing none
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource
    void retrieveCalendars_transientApiError_failsWithoutInvalidatingTheCredential(int statusCode, @Nullable String reason) {
        startService(createErrorResponder(statusCode, reason));

        CompletableFuture<CalendarsResult> future = service.retrieveCalendars();
        clock.tick();

        assertThat(future).failsWithin(Duration.ZERO)
                          .withThrowableThat()
                          .havingCause()
                          .isNotInstanceOf(CalendarAuthorisationException.class)
                          .withMessage("Failed to " + CALENDAR_LIST_SYNC + ": " + describeResponse(statusCode, reason))
                          .havingCause()
                          .isInstanceOfSatisfying(HttpResponseException.class, http -> assertThat(http.statusCode()).isEqualTo(statusCode));
        assertThat(tokenManager.invalidations()).isEmpty();
    }

    static Stream<Arguments> retrieveCalendars_transientApiError_failsWithoutInvalidatingTheCredential() {
        return Stream.of(arguments(INTERNAL_SERVER_ERROR_500, null),
                         arguments(FORBIDDEN_403, USER_RATE_LIMIT_EXCEEDED_REASON));
    }

    /// A listing answered just before the stop is read on the executor after it, where the service is found stopped, so the caller's future stays incomplete
    /// and a rejection leaves the credential alone.
    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = {OK_200, UNAUTHORIZED_401})
    void retrieveCalendars_answeredJustBeforeTheServiceStops_neverCompletes(int statusCode) {
        List<PendingCall> pendingCalls = startServiceWithDeferredCalls();
        CompletableFuture<CalendarsResult> future = service.retrieveCalendars();
        clock.tick();

        takeOnlyPendingCall(pendingCalls).respond(statusCode, HOME_CALENDAR_LIST_JSON);
        service.stop();
        clock.tick();

        assertThat(future).isNotDone();
        assertThat(tokenManager.invalidations()).isEmpty();
    }

    /// A deleted client project surfaces as a forbidden response whose reason is not a rate limit, and a revoked grant as an unauthorised one; a forbidden
    /// response listing no reason counts as permanent too. Each invalidates the access token the request carried and fails as an authorisation failure.
    ///
    /// @param reason the error reason the response lists, or `null` for a response listing none
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource
    void retrieveCalendars_permanentAuthError_invalidatesCredentialSoUserIsPromptedToReconnect(int statusCode, @Nullable String reason) {
        startService(createErrorResponder(statusCode, reason));

        CompletableFuture<CalendarsResult> future = service.retrieveCalendars();
        clock.tick();

        String rejection = describeRejection(statusCode, reason, CALENDAR_LIST_SYNC);
        assertThat(future).failsWithin(Duration.ZERO)
                          .withThrowableThat()
                          .havingCause()
                          .isInstanceOf(CalendarAuthorisationException.class)
                          .withMessage(rejection)
                          .havingCause()
                          .isInstanceOfSatisfying(HttpResponseException.class, http -> assertThat(http.statusCode()).isEqualTo(statusCode));
        assertThat(tokenManager.invalidations()).containsExactly(rejection);
        assertThat(tokenManager.invalidatedAccessTokens()).containsExactly(ACCESS_TOKEN);
    }

    static Stream<Arguments> retrieveCalendars_permanentAuthError_invalidatesCredentialSoUserIsPromptedToReconnect() {
        return Stream.of(arguments(FORBIDDEN_403, "forbidden"),
                         arguments(FORBIDDEN_403, null),
                         arguments(UNAUTHORIZED_401, AUTH_ERROR_REASON));
    }

    /// The token to invalidate is the one the rejected requests carried: a fresher token that arrived meanwhile has not been rejected.
    @Test
    void retrieveCalendars_credentialRejected_invalidatesTheTokenTheSyncCarried() {
        List<PendingCall> pendingCalls = startServiceWithDeferredCalls();

        CompletableFuture<CalendarsResult> future = service.retrieveCalendars();
        clock.tick();
        tokenManager.setState(new AuthState.Success("access-token-2"));
        clock.tick();
        takeOnlyPendingCall(pendingCalls).respond(UNAUTHORIZED_401, "{}");
        clock.tick();

        assertThat(future).failsWithin(Duration.ZERO);
        assertThat(tokenManager.invalidatedAccessTokens()).containsExactly(ACCESS_TOKEN);
    }

    @Test
    void retrieveCalendars_notAuthenticated_reportsNotYetAuthenticatedWithoutApiCall() {
        // Before the token exchange completes the service has no access token: it must not issue an unauthenticated API call (which Google would reject with
        // 401, and the service would misread as a permanent credential rejection and tear the integration down), but report the not-yet-authenticated state —
        // never a successful empty list, which callers would trust as "the account has no calendars" — leaving the credential untouched.
        startService(new FakeOAuth2TokenManager(new AuthState.TransientFailure("Initialising")));

        CompletableFuture<CalendarsResult> future = service.retrieveCalendars();
        clock.tick();

        assertThat(future).succeedsWithin(Duration.ZERO).isSameAs(NotYetAuthenticated.INSTANCE);
        verify(httpClient, never()).newCall(any());
        assertThat(tokenManager.invalidations()).isEmpty();
    }

    @Test
    void fetchEvents_skipsEventsMissingStartOrEnd() {
        startServiceWithEventsAnswering(request -> ok(request, """
                                                               {"items":[
                                                                 {"id":"e0","summary":"No times"},
                                                                 {"id":"e1","summary":"Timed",
                                                                  "start":{"dateTime":"2026-06-20T10:00:00Z"},"end":{"dateTime":"2026-06-20T11:00:00Z"}}
                                                               ]}"""));

        assertThat(fetchEvents(retrieveCalendars().getFirst()))
                .extracting(CalendarEvent::summary)
                .containsExactly("Timed");
    }

    /// A grant missing the events scope, as when the user unticks it on the consent screen, gets a forbidden response from `events.list`, and a revoked grant
    /// an unauthorised one. Both invalidate the access token the request carried and fail as an authorisation failure that names no calendar id.
    ///
    /// @param reason the error reason the response lists, or `null` for a response listing none
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource
    void fetchEvents_credentialRejected_invalidatesCredentialAndFailsAsAnAuthorisationFailure(int statusCode, @Nullable String reason) {
        startServiceWithEventsAnswering(createErrorResponder(statusCode, reason));

        String rejection = describeRejection(statusCode, reason, EVENTS_FETCH + "Hom…");
        assertThat(fetchEventsFuture(retrieveCalendars().getFirst())).failsWithin(Duration.ZERO)
                                                                     .withThrowableThat()
                                                                     .satisfies(GoogleCalendarServiceTest::assertNamesNoCalendarId)
                                                                     .havingCause()
                                                                     .isInstanceOf(CalendarAuthorisationException.class)
                                                                     .withMessage(rejection)
                                                                     .havingCause()
                                                                     .isInstanceOfSatisfying(HttpResponseException.class,
                                                                                             http -> assertThat(http.statusCode()).isEqualTo(statusCode));
        assertThat(tokenManager.invalidations()).containsExactly(rejection);
        assertThat(tokenManager.invalidatedAccessTokens()).containsExactly(ACCESS_TOKEN);
    }

    static Stream<Arguments> fetchEvents_credentialRejected_invalidatesCredentialAndFailsAsAnAuthorisationFailure() {
        return Stream.of(arguments(FORBIDDEN_403, INSUFFICIENT_PERMISSIONS_REASON),
                         arguments(UNAUTHORIZED_401, AUTH_ERROR_REASON),
                         arguments(UNAUTHORIZED_401, null));
    }

    /// An events listing answered just before the stop is read on the executor after it, where the service is found stopped, so the caller's future stays
    /// incomplete and a rejection leaves the credential alone.
    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = {OK_200, UNAUTHORIZED_401})
    void fetchEvents_answeredJustBeforeTheServiceStops_neverCompletes(int statusCode) {
        List<PendingCall> pendingCalls = startServiceWithDeferredCalls();
        CompletableFuture<List<CalendarEvent>> events = retrieveEventsCalendar(pendingCalls).fetchEvents(FROM, TO);
        clock.tick();

        takeOnlyPendingCall(pendingCalls).respond(statusCode, "{}");
        service.stop();
        clock.tick();

        assertThat(events).isNotDone();
        assertThat(tokenManager.invalidations()).isEmpty();
    }

    /// A quota limit or a failure confined to one calendar leaves in place the credential every calendar is read with, and the failure names no calendar id
    /// and none of Google's own message, which can quote it.
    ///
    /// @param reason the error reason the response lists, or `null` for a response listing none
    @ParameterizedTest(name = "{0}")
    @MethodSource
    void fetchEvents_otherErrors_failWithoutInvalidatingTheCredential(String caseName,
                                                                      Function<Request, Response> responder,
                                                                      int statusCode,
                                                                      @Nullable String reason) {
        startServiceWithEventsAnswering(responder);

        String failureMessage = "Failed to " + EVENTS_FETCH + "Hom…: " + describeResponse(statusCode, reason);
        assertThat(fetchEventsFuture(retrieveCalendars().getFirst())).failsWithin(Duration.ZERO)
                                                                     .withThrowableThat()
                                                                     .satisfies(GoogleCalendarServiceTest::assertNamesNoCalendarId)
                                                                     .havingCause()
                                                                     .isNotInstanceOf(CalendarAuthorisationException.class)
                                                                     .withMessage(failureMessage)
                                                                     .havingCause()
                                                                     .isInstanceOfSatisfying(HttpResponseException.class,
                                                                                             http -> assertThat(http.statusCode()).isEqualTo(statusCode));
        assertThat(tokenManager.invalidations()).isEmpty();
    }

    static Stream<Arguments> fetchEvents_otherErrors_failWithoutInvalidatingTheCredential() {
        return Stream.of(arguments("rate limit", createErrorResponder(FORBIDDEN_403, RATE_LIMIT_EXCEEDED_REASON), FORBIDDEN_403, RATE_LIMIT_EXCEEDED_REASON),
                         arguments("another reason",
                                   createErrorResponder(FORBIDDEN_403, FORBIDDEN_FOR_NON_ORGANIZER_REASON),
                                   FORBIDDEN_403,
                                   FORBIDDEN_FOR_NON_ORGANIZER_REASON),
                         arguments("no error listed", createErrorResponder(FORBIDDEN_403, null), FORBIDDEN_403, null),
                         arguments("not found", createErrorResponder(NOT_FOUND_404, "notFound"), NOT_FOUND_404, "notFound"),
                         arguments("no JSON body",
                                   (Function<Request, Response>) request -> response(request, BAD_GATEWAY_502, "<html>Bad Gateway</html>"),
                                   BAD_GATEWAY_502,
                                   null));
    }

    @Test
    void googleCalendar_toString_redactsIdAndName() {
        // A Google calendar id is the account's email address, so it is redacted alongside the title.
        startService(request -> ok(request, """
                                            {"items":[{"id":"owner@example.com","summary":"Private Trip"}],"nextSyncToken":"sync-1"}"""));

        assertThat(retrieveCalendars().getFirst()).asString()
                                                  .doesNotContain("owner@example.com")
                                                  .doesNotContain("Private Trip")
                                                  .contains("own…")
                                                  .contains("Pri…");
    }

    @Test
    void createHttpClient_givesUpOnEachExchangeAfterTheTimeout() {
        OkHttpClient client = createUnstubbedService(TIMEOUT).createHttpClient();

        assertGivesUpAfter(client, TIMEOUT);
    }

    /// A zero timeout would turn every timeout off, and a call that hangs would then hold up every later sync.
    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void constructor_timeoutNotPositive_isRejected(long timeoutSeconds) {
        assertThatThrownBy(() -> createUnstubbedService(Duration.ofSeconds(timeoutSeconds))).isInstanceOf(IllegalArgumentException.class);
    }

    private GoogleCalendarService createUnstubbedService(Duration timeout) {
        return createService(new FakeOAuth2TokenManager(new AuthState.Success(ACCESS_TOKEN)), Optional.empty(), Optional.empty(), timeout, null);
    }

    /// Serves one calendar, [#EVENTS_CALENDAR_ID] titled "Home", and responds to each request for its events with what `eventsResponder` returns for it.
    private void startServiceWithEventsAnswering(Function<Request, Response> eventsResponder) {
        startService(request -> isEventsRequest(request, EVENTS_CALENDAR_ID) ? eventsResponder.apply(request) : ok(request, EVENTS_CALENDAR_LIST_JSON));
    }

    private void startService(Function<Request, Response> responder) {
        issuedRequests = stubCalls(httpClient, responder);
        startAuthenticatedService();
    }

    /// @return the calls the service enqueues, each pending until the test responds to or fails it
    private List<PendingCall> startServiceWithDeferredCalls() {
        List<PendingCall> pendingCalls = stubDeferredCalls(httpClient);
        startAuthenticatedService();
        return pendingCalls;
    }

    private void startAuthenticatedService() {
        startService(new FakeOAuth2TokenManager(new AuthState.Success(ACCESS_TOKEN)));
    }

    private void startService(FakeOAuth2TokenManager fakeTokenManager) {
        tokenManager = fakeTokenManager;
        service = createService(fakeTokenManager, Optional.empty(), Optional.empty(), TIMEOUT, httpClient);
        service.start();
        clock.tick(); // run the token-state callback, which sets the access token for a Success state
    }

    /// @param stubbedHttpClient the client the service calls through, or `null` for the one it creates itself
    private GoogleCalendarService createService(OAuth2TokenManager oAuth2TokenManager,
                                                Optional<String> authCode,
                                                Optional<String> codeVerifier,
                                                Duration timeout,
                                                @Nullable OkHttpClient stubbedHttpClient) {
        return new GoogleCalendarService(() -> executor,
                                         oAuth2TokenManager,
                                         REDIRECT_URI,
                                         authCode,
                                         codeVerifier,
                                         timeout,
                                         SUBJECT,
                                         new ListenerBackedTaskExceptionHandlerRegistry()) {
            @Override
            OkHttpClient createHttpClient() {
                return stubbedHttpClient == null ? super.createHttpClient() : stubbedHttpClient;
            }
        };
    }

    /// Retrieves the calendar list through `pendingCalls`, answering it with [#EVENTS_CALENDAR_LIST_JSON].
    private Calendar retrieveEventsCalendar(List<PendingCall> pendingCalls) {
        CompletableFuture<CalendarsResult> retrieval = service.retrieveCalendars();
        clock.tick();
        takeOnlyPendingCall(pendingCalls).respond(OK_200, EVENTS_CALENDAR_LIST_JSON);
        clock.tick();
        return calendarsOf(retrieval).getFirst();
    }

    private List<Calendar> retrieveCalendars() {
        CompletableFuture<CalendarsResult> future = service.retrieveCalendars();
        clock.tick();
        return calendarsOf(future);
    }

    private static List<Calendar> calendarsOf(CompletableFuture<CalendarsResult> future) {
        assertThat(future).succeedsWithin(Duration.ZERO);
        return switch (future.getNow(null)) {
            case Calendars(List<Calendar> calendars) -> calendars;
            case NotYetAuthenticated _ -> throw new AssertionError("expected a calendar list, but the service reported it is not yet authenticated");
        };
    }

    private List<CalendarEvent> fetchEvents(Calendar calendar) {
        CompletableFuture<List<CalendarEvent>> future = fetchEventsFuture(calendar);
        assertThat(future).succeedsWithin(Duration.ZERO);
        return future.getNow(null);
    }

    private CompletableFuture<List<CalendarEvent>> fetchEventsFuture(Calendar calendar) {
        CompletableFuture<List<CalendarEvent>> future = calendar.fetchEvents(FROM, TO);
        clock.tick();
        return future;
    }

    private static boolean hasPageToken(Request request, String pageToken) {
        return pageToken.equals(request.url().queryParameter(PAGE_TOKEN_PARAM));
    }

    private static boolean hasSyncToken(Request request, String syncToken) {
        return syncToken.equals(request.url().queryParameter(SYNC_TOKEN_PARAM));
    }

    private static boolean isEventsRequest(Request request, String calendarId) {
        return request.url().pathSegments().equals(List.of("calendar", "v3", "calendars", calendarId, "events"));
    }

    private static Response gone(Request request) {
        return response(request, GONE_410, """
                                           {"error":{"code":410,"message":"Sync token is no longer valid."}}""");
    }

    /// @param reason the error reason the responses list, or `null` for responses listing none
    private static Function<Request, Response> createErrorResponder(int statusCode, @Nullable String reason) {
        return request -> createErrorResponse(request, statusCode, reason);
    }

    /// Google's message quotes [#EVENTS_CALENDAR_ID], as a real one can quote the calendar a request named.
    ///
    /// @param reason the reason of the one error the response lists, or `null` for a response listing none
    private static Response createErrorResponse(Request request, int statusCode, @Nullable String reason) {
        String message = "Request for calendar " + EVENTS_CALENDAR_ID + " refused";
        String errors = reason == null ? "" : """
                                              ,"errors":[{"domain":"global","reason":"%s","message":"%s"}]""".formatted(reason, message);
        return response(request, statusCode, """
                                             {"error":{"code":%d,"message":"%s"%s}}""".formatted(statusCode, message, errors));
    }

    private static Response createHomeCalendarListResponse(Request request) {
        return ok(request, HOME_CALENDAR_LIST_JSON);
    }

    private static Response ok(Request request, String json) {
        return response(request, OK_200, json);
    }

    /// @param reason the error reason the response lists, or `null` for a response listing none
    private static String describeRejection(int statusCode, @Nullable String reason, String requestDescription) {
        return "Google Calendar API rejected the credential (" + describeResponse(statusCode, reason) + ") trying to " + requestDescription;
    }

    /// @param reason the error reason the response lists, or `null` for a response listing none
    private static String describeResponse(int statusCode, @Nullable String reason) {
        return "HTTP " + statusCode + (reason == null ? "" : " " + reason);
    }

    /// Asserts that `failure` and its causes never name [#EVENTS_CALENDAR_ID]: the failure is logged, and a real calendar id is the user's email address.
    private static void assertNamesNoCalendarId(Throwable failure) {
        assertThat(getStackTraceAsString(failure)).doesNotContain(EVENTS_CALENDAR_ID);
    }
}
