package net.yudichev.jiotty.connector.icloud.calendar;

import net.yudichev.jiotty.common.async.ListenerBackedTaskExceptionHandlerRegistry;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.misc.SharedUpstreamOutage;
import net.yudichev.jiotty.common.rest.HttpResponseException;
import net.yudichev.jiotty.common.rest.OkHttpStubs.PendingCall;
import net.yudichev.jiotty.common.rest.RestClients;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.common.testutil.LogCapture;
import net.yudichev.jiotty.common.time.calendar.Calendar;
import net.yudichev.jiotty.common.time.calendar.CalendarAuthorisationException;
import net.yudichev.jiotty.common.time.calendar.CalendarEvent;
import net.yudichev.jiotty.common.time.calendar.CalendarService.CalendarsResult;
import net.yudichev.jiotty.common.time.calendar.CalendarService.CalendarsResult.Calendars;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static net.yudichev.jiotty.common.rest.HttpStatuses.SERVICE_UNAVAILABLE_503;
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;
import static net.yudichev.jiotty.common.rest.OkHttpClientAssertions.assertGivesUpAfter;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.stubCalls;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.stubDeferredCalls;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.takeOnlyPendingCall;
import static net.yudichev.jiotty.connector.icloud.calendar.CalDavClient.REPORT;
import static net.yudichev.jiotty.connector.icloud.calendar.CalDavClient.WELL_KNOWN_CALDAV_URL;
import static net.yudichev.jiotty.connector.icloud.calendar.CalDavFixtures.ACCOUNT_ID;
import static net.yudichev.jiotty.connector.icloud.calendar.CalDavFixtures.CALENDARS_RESPONSE;
import static net.yudichev.jiotty.connector.icloud.calendar.CalDavFixtures.CALENDAR_HREF;
import static net.yudichev.jiotty.connector.icloud.calendar.CalDavFixtures.CALENDAR_TITLE;
import static net.yudichev.jiotty.connector.icloud.calendar.CalDavFixtures.HOME;
import static net.yudichev.jiotty.connector.icloud.calendar.CalDavFixtures.HOME_RESPONSE;
import static net.yudichev.jiotty.connector.icloud.calendar.CalDavFixtures.createErrorResponse;
import static net.yudichev.jiotty.connector.icloud.calendar.CalDavFixtures.createMultiStatusResponse;
import static net.yudichev.jiotty.connector.icloud.calendar.IcloudCalendarService.AUTH_STATE_DELIVERY_TASK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.list;

@ExtendWith(MockitoExtension.class)
class IcloudCalendarServiceTest {
    private static final String USERNAME = "alice@example.com";
    private static final String PASSWORD = "abcd-efgh-ijkl-mnop";
    private static final String LOG_SUBJECT_ID = "test-user";
    private static final String EVENTS_RESPONSE = """
                                                  <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                                                    <d:response>
                                                      <d:href>%sdentist.ics</d:href>
                                                      <d:propstat>
                                                        <d:prop><c:calendar-data>BEGIN:VCALENDAR
                                                  VERSION:2.0
                                                  PRODID:-//Apple Inc.//iCloud//EN
                                                  BEGIN:VEVENT
                                                  UID:dentist-uid
                                                  DTSTAMP:20260601T000000Z
                                                  DTSTART:20260620T100000Z
                                                  DTEND:20260620T110000Z
                                                  SUMMARY:Dentist
                                                  LOCATION:12 Harley Street
                                                  END:VEVENT
                                                  END:VCALENDAR
                                                  </c:calendar-data></d:prop>
                                                        <d:status>HTTP/1.1 200 OK</d:status>
                                                      </d:propstat>
                                                    </d:response>
                                                  </d:multistatus>""".formatted(CALENDAR_HREF);
    /// [#EVENTS_RESPONSE] with a second entry, a copy of its first whose `VEVENT` is never closed.
    private static final String EVENTS_WITH_UNREADABLE_ENTRY_RESPONSE = EVENTS_RESPONSE.replace(
            "</d:multistatus>",
            EVENTS_RESPONSE.substring(EVENTS_RESPONSE.indexOf("<d:response>"), EVENTS_RESPONSE.indexOf("</d:multistatus>")).replace("END:VEVENT\n", "")
            + "</d:multistatus>");
    /// iCloud folds a long line onto the next one, which starts with a space, and the XML the data travels in delivers its line breaks as bare `\n`.
    private static final String FOLDED_LINE_EVENTS_RESPONSE = EVENTS_RESPONSE.replace("LOCATION:12 Harley Street\n", "LOCATION:12 Harley\n  Street\n");
    private static final Instant FROM = Instant.parse("2026-06-20T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-22T00:00:00Z");
    private final ProgrammableClock clock = new ProgrammableClock();
    private final List<AuthState> authStates = new ArrayList<>();
    private final ListenerBackedTaskExceptionHandlerRegistry taskFailureRegistry = new ListenerBackedTaskExceptionHandlerRegistry();
    @Mock
    private OkHttpClient httpClient;
    private List<Request> requests = List.of();
    private SchedulingExecutor executor;
    private IcloudCalendarService service;

    @BeforeEach
    void setUp() {
        executor = clock.createSingleThreadedSchedulingExecutor("test");
        service = new IcloudCalendarService(() -> executor,
                                            USERNAME,
                                            PASSWORD,
                                            Duration.ofSeconds(30),
                                            LOG_SUBJECT_ID,
                                            taskFailureRegistry) {
            @Override
            OkHttpClient createHttpClient() {
                return httpClient;
            }
        };
        service.subscribeToAuthState(authStates::add);
    }

    @AfterEach
    void tearDown() {
        service.stop();
    }

    @Test
    void retrieveCalendars_discoversTheHomeAndListsTheCalendarsInIt() {
        answerWith(IcloudCalendarServiceTest::respondAsIcloud);
        service.start();

        assertThat(retrieveCalendars()).singleElement().satisfies(calendar -> {
            assertThat(calendar.id()).isEqualTo(CALENDAR_HREF);
            assertThat(calendar.name()).isEqualTo(CALENDAR_TITLE);
        });
        assertThat(authStates).last().isInstanceOf(AuthState.Success.class);
        assertThat(requests).extracting(request -> request.url().host()).containsExactly(WELL_KNOWN_CALDAV_URL.host(), HOME.host());
        assertThat(requests).allSatisfy(request -> assertThat(request.header(AUTHORIZATION)).isEqualTo(Credentials.basic(USERNAME, PASSWORD)));
    }

    /// An unchanged calendar keeps its instance across retrievals, so a consumer comparing calendars by identity sees a change only when one happens.
    @Test
    void retrieveCalendars_again_returnsTheSameInstanceOfAnUnchangedCalendar() {
        answerWith(IcloudCalendarServiceTest::respondAsIcloud);
        service.start();
        Calendar calendar = retrieveCalendars().getFirst();

        assertThat(retrieveCalendars()).singleElement().isSameAs(calendar);
    }

    @Test
    void retrieveCalendars_calendarRenamed_returnsANewInstance() {
        answerWith(IcloudCalendarServiceTest::respondAsIcloud);
        service.start();
        Calendar calendar = retrieveCalendars().getFirst();
        answerWith(request -> WELL_KNOWN_CALDAV_URL.encodedPath().equals(request.url().encodedPath())
                              ? createMultiStatusResponse(request, HOME_RESPONSE)
                              : createMultiStatusResponse(request, CALENDARS_RESPONSE.replace(CALENDAR_TITLE, "Renamed")));

        assertThat(retrieveCalendars()).singleElement().isNotSameAs(calendar).returns("Renamed", Calendar::name);
    }

    /// The credentials passed discovery, so a failing listing is a transient fault of the listing alone.
    @Test
    void retrieveCalendars_listingFails_failsWithTheStatus() {
        answerWith(request -> WELL_KNOWN_CALDAV_URL.encodedPath().equals(request.url().encodedPath())
                              ? createMultiStatusResponse(request, HOME_RESPONSE)
                              : createErrorResponse(request, SERVICE_UNAVAILABLE_503));
        service.start();

        assertFailsWithStatus(retrieveCalendarsFuture(), SERVICE_UNAVAILABLE_503);
        assertThat(authStates).last().isInstanceOf(AuthState.Success.class);
    }

    @Test
    void retrieveCalendars_credentialsRejected_failsPermanently() {
        answerWith(request -> createErrorResponse(request, UNAUTHORIZED_401));
        service.start();

        assertThat(retrieveCalendarsFuture()).failsWithin(Duration.ZERO)
                                             .withThrowableThat()
                                             .havingCause()
                                             .isInstanceOf(CalendarAuthorisationException.class)
                                             .havingCause()
                                             .isInstanceOfSatisfying(HttpResponseException.class,
                                                                     httpFailure -> assertThat(httpFailure.statusCode()).isEqualTo(UNAUTHORIZED_401));
        assertThat(authStates).last().isInstanceOf(AuthState.PermanentFailure.class);
    }

    /// The status reaches the caller as an [HttpResponseException], which [SharedUpstreamOutage#indicatesSharedOutage] classifies as a shared outage when it
    /// is a 5xx.
    @Test
    void retrieveCalendars_serverError_failsTransiently() {
        answerWith(request -> createErrorResponse(request, SERVICE_UNAVAILABLE_503));
        service.start();

        assertFailsWithStatus(retrieveCalendarsFuture(), SERVICE_UNAVAILABLE_503);
        assertThat(authStates).last().isInstanceOf(AuthState.TransientFailure.class);
    }

    /// A transport failure publishes no auth state, so subscribers keep the initial [AuthState.TransientFailure].
    @Test
    void retrieveCalendars_transportFailure_failsLeavingTheAuthStateAlone() {
        List<PendingCall> pendingCalls = stubDeferredCalls(httpClient);
        service.start();

        CompletableFuture<CalendarsResult> future = service.retrieveCalendars();
        clock.tick();
        takeOnlyPendingCall(pendingCalls).fail(new IOException("connection reset"));
        clock.tick();

        assertThat(future).failsWithin(Duration.ZERO).withThrowableThat().withRootCauseInstanceOf(IOException.class);
        assertThat(authStates).singleElement().isInstanceOf(AuthState.TransientFailure.class);
    }

    /// A retrieval asked for while another is in flight starts once that one has finished, so an older retrieval's outcome can never overwrite the auth state a
    /// newer one published.
    @Test
    void retrieveCalendars_whileARetrievalIsInFlight_startsOnceItFinishes() {
        List<PendingCall> pendingCalls = stubDeferredCalls(httpClient);
        service.start();

        CompletableFuture<CalendarsResult> firstRetrieval = service.retrieveCalendars();
        CompletableFuture<CalendarsResult> secondRetrieval = service.retrieveCalendars();
        clock.tick();
        PendingCall firstDiscoveryCall = takeOnlyPendingCall(pendingCalls);
        assertThat(pendingCalls).as("the second retrieval waits for the first").isEmpty();

        firstDiscoveryCall.respond(createErrorResponse(firstDiscoveryCall.request(), SERVICE_UNAVAILABLE_503));
        clock.tick();
        assertThat(firstRetrieval).isCompletedExceptionally();
        PendingCall secondDiscoveryCall = takeOnlyPendingCall(pendingCalls);
        secondDiscoveryCall.respond(createMultiStatusResponse(secondDiscoveryCall.request(), HOME_RESPONSE));
        clock.tick();
        PendingCall listingCall = takeOnlyPendingCall(pendingCalls);
        listingCall.respond(createMultiStatusResponse(listingCall.request(), CALENDARS_RESPONSE));
        clock.tick();

        assertThat(secondRetrieval).succeedsWithin(Duration.ZERO);
        assertThat(authStates).last().isInstanceOf(AuthState.Success.class);
    }

    @Test
    void fetchEvents_readsEachEventOfTheRange() {
        answerWith(IcloudCalendarServiceTest::respondAsIcloud);
        service.start();

        CompletableFuture<List<CalendarEvent>> events = retrieveCalendars().getFirst().fetchEvents(FROM, TO);
        clock.tick();

        assertThat(events).succeedsWithin(Duration.ZERO)
                          .asInstanceOf(list(CalendarEvent.class))
                          .singleElement()
                          .satisfies(event -> {
                              assertThat(event.id()).isEqualTo("dentist-uid");
                              assertThat(event.summary()).isEqualTo("Dentist");
                              assertThat(event.location()).hasValue("12 Harley Street");
                              assertThat(Instant.from(event.start())).isEqualTo(Instant.parse("2026-06-20T10:00:00Z"));
                          });
    }

    @Test
    void fetchEvents_reportFails_failsWithTheStatus() {
        answerWith(request -> REPORT.equals(request.method())
                              ? createErrorResponse(request, SERVICE_UNAVAILABLE_503)
                              : respondAsIcloud(request));
        service.start();

        CompletableFuture<List<CalendarEvent>> events = retrieveCalendars().getFirst().fetchEvents(FROM, TO);
        clock.tick();

        assertFailsWithStatus(events, SERVICE_UNAVAILABLE_503);
    }

    @Test
    void fetchEvents_lineFoldedAcrossBareLineBreaks_readsTheUnfoldedValue() {
        answerWith(request -> REPORT.equals(request.method())
                              ? createMultiStatusResponse(request, FOLDED_LINE_EVENTS_RESPONSE)
                              : respondAsIcloud(request));
        service.start();

        CompletableFuture<List<CalendarEvent>> events = retrieveCalendars().getFirst().fetchEvents(FROM, TO);
        clock.tick();

        assertThat(events).succeedsWithin(Duration.ZERO)
                          .asInstanceOf(list(CalendarEvent.class))
                          .singleElement()
                          .satisfies(event -> assertThat(event.location()).hasValue("12 Harley Street"));
    }

    /// An entry the parser cannot read costs only that entry, and the log line naming it quotes none of its data.
    @Test
    void fetchEvents_unreadableEntry_skipsItAndReadsTheRest() {
        answerWith(request -> REPORT.equals(request.method())
                              ? createMultiStatusResponse(request, EVENTS_WITH_UNREADABLE_ENTRY_RESPONSE)
                              : respondAsIcloud(request));
        service.start();
        Calendar calendar = retrieveCalendars().getFirst();

        List<LogCapture.CapturedLine> lines;
        CompletableFuture<List<CalendarEvent>> events;
        try (var calendarLog = LogCapture.capture(IcloudCalendar.class, Level.INFO)) {
            events = calendar.fetchEvents(FROM, TO);
            clock.tick();
            lines = calendarLog.lines();
        }

        assertThat(events).succeedsWithin(Duration.ZERO)
                          .asInstanceOf(list(CalendarEvent.class))
                          .singleElement()
                          .satisfies(event -> assertThat(event.id()).isEqualTo("dentist-uid"));
        assertThat(lines).flatMap(line -> line.text().toList())
                         .anySatisfy(text -> assertThat(text).contains("unreadable at line"))
                         .noneMatch(text -> Stream.of(CALENDAR_TITLE, "Dentist", "Harley Street").anyMatch(text::contains));
    }

    @Test
    void stop_shutsTheHttpClientDown() {
        answerWith(IcloudCalendarServiceTest::respondAsIcloud);
        service.start();
        assertThat(httpClient.dispatcher().executorService().isShutdown()).isFalse();

        service.stop();

        assertThat(httpClient.dispatcher().executorService().isShutdown()).isTrue();
    }

    @Test
    void retrieveCalendars_authStateSubscriberThrowing_reportsTheTaskFailure() {
        List<String> failedTaskDescriptions = new ArrayList<>();
        taskFailureRegistry.addExceptionHandler((taskDescription, _) -> failedTaskDescriptions.add(taskDescription));
        service.subscribeToAuthState(_ -> {
            throw new IllegalStateException("subscriber failure");
        });
        answerWith(IcloudCalendarServiceTest::respondAsIcloud);
        service.start();

        assertThat(retrieveCalendars()).hasSize(1);
        assertThat(failedTaskDescriptions).containsOnly(AUTH_STATE_DELIVERY_TASK);
    }

    /// The failure an operator reads names whose calendar failed, but never its href, which opens with the account id.
    @Test
    void fetchEvents_transportFailure_namesTheSubjectAndTheRedactedCalendar() {
        answerWith(IcloudCalendarServiceTest::respondAsIcloud);
        service.start();
        Calendar calendar = retrieveCalendars().getFirst();
        List<PendingCall> pendingCalls = stubDeferredCalls(httpClient);

        CompletableFuture<List<CalendarEvent>> events = calendar.fetchEvents(FROM, TO);
        clock.tick();
        takeOnlyPendingCall(pendingCalls).fail(new IOException("network down"));
        clock.tick();

        assertThat(events).failsWithin(Duration.ZERO)
                          .withThrowableThat()
                          .withMessageContaining("[" + LOG_SUBJECT_ID + "] iCloud events of calendar Fam")
                          .withMessageNotContaining(ACCOUNT_ID)
                          .withRootCauseInstanceOf(IOException.class);
    }

    /// A redirect would carry the account's credentials to wherever it pointed.
    @Test
    void createHttpClient_followsNoRedirectAndGivesUpAfterTheTimeout() {
        Duration timeout = Duration.ofSeconds(30);

        OkHttpClient client = createUnstubbedHttpClient(timeout);

        assertThat(client.followRedirects()).isFalse();
        assertThat(client.followSslRedirects()).isFalse();
        assertGivesUpAfter(client, timeout);
    }

    /// A zero timeout would turn every timeout off, and a call that hangs would then hold up every later retrieval.
    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void constructor_timeoutNotPositive_isRejected(long timeoutSeconds) {
        assertThatThrownBy(() -> newUnstubbedService(Duration.ofSeconds(timeoutSeconds))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fetchEvents_afterTheServiceStopped_neverCompletes() {
        answerWith(IcloudCalendarServiceTest::respondAsIcloud);
        service.start();
        Calendar calendar = retrieveCalendars().getFirst();
        int requestsBeforeStop = requests.size();
        service.stop();

        CompletableFuture<List<CalendarEvent>> events = calendar.fetchEvents(FROM, TO);
        clock.tick();

        assertThat(events).isNotDone();
        assertThat(requests).hasSize(requestsBeforeStop);
    }

    @Test
    void retrieveCalendars_queuedWhenTheServiceStops_neverCompletes() {
        answerWith(IcloudCalendarServiceTest::respondAsIcloud);
        service.start();

        CompletableFuture<CalendarsResult> future = service.retrieveCalendars();
        service.stop();
        clock.tick();

        assertThat(future).isNotDone();
        assertThat(requests).isEmpty();
    }

    @Test
    void retrieveCalendars_afterTheServiceStopped_neverCompletes() {
        answerWith(IcloudCalendarServiceTest::respondAsIcloud);
        service.start();
        service.stop();

        CompletableFuture<CalendarsResult> future = service.retrieveCalendars();
        clock.tick();

        assertThat(future).isNotDone();
        assertThat(requests).isEmpty();
    }

    /// Stopping fails the requests in flight, and that failure reaches no caller.
    @Test
    void retrieveCalendars_discoveryInFlightWhenTheServiceStops_neverCompletes() {
        List<PendingCall> pendingCalls = stubDeferredCalls(httpClient);
        service.start();
        CompletableFuture<CalendarsResult> future = service.retrieveCalendars();
        clock.tick();
        PendingCall discoveryCall = takeOnlyPendingCall(pendingCalls);

        service.stop();
        discoveryCall.fail(new IOException("Canceled"));
        clock.tick();

        assertThat(future).isNotDone();
        assertThat(authStates).singleElement().isInstanceOf(AuthState.TransientFailure.class);
    }

    /// Stopping fails the requests in flight, and that failure reaches no caller.
    @Test
    void retrieveCalendars_listingInFlightWhenTheServiceStops_neverCompletes() {
        List<PendingCall> pendingCalls = stubDeferredCalls(httpClient);
        service.start();
        CompletableFuture<CalendarsResult> future = service.retrieveCalendars();
        clock.tick();
        PendingCall discoveryCall = takeOnlyPendingCall(pendingCalls);
        discoveryCall.respond(createMultiStatusResponse(discoveryCall.request(), HOME_RESPONSE));
        clock.tick();
        PendingCall listingCall = takeOnlyPendingCall(pendingCalls);

        service.stop();
        listingCall.fail(new IOException("Canceled"));
        clock.tick();

        assertThat(future).isNotDone();
    }

    /// A discovery answered just before the stop is read on the executor after it. The service is found stopped there, so the caller's future never completes.
    @Test
    void retrieveCalendars_discoveryAnsweredJustBeforeTheServiceStops_neverCompletes() {
        List<PendingCall> pendingCalls = stubDeferredCalls(httpClient);
        service.start();
        CompletableFuture<CalendarsResult> future = service.retrieveCalendars();
        clock.tick();
        PendingCall discoveryCall = takeOnlyPendingCall(pendingCalls);

        discoveryCall.respond(createMultiStatusResponse(discoveryCall.request(), HOME_RESPONSE));
        service.stop();
        clock.tick();

        assertThat(future).isNotDone();
        assertThat(pendingCalls).as("no listing is requested").isEmpty();
        assertThat(authStates).singleElement().isInstanceOf(AuthState.TransientFailure.class);
    }

    /// A listing answered just before the stop is read on the executor after it. The service is found stopped there, so the caller's future never completes.
    @Test
    void retrieveCalendars_listingAnsweredJustBeforeTheServiceStops_neverCompletes() {
        List<PendingCall> pendingCalls = stubDeferredCalls(httpClient);
        service.start();
        CompletableFuture<CalendarsResult> future = service.retrieveCalendars();
        clock.tick();
        PendingCall discoveryCall = takeOnlyPendingCall(pendingCalls);
        discoveryCall.respond(createMultiStatusResponse(discoveryCall.request(), HOME_RESPONSE));
        clock.tick();
        PendingCall listingCall = takeOnlyPendingCall(pendingCalls);

        listingCall.respond(createMultiStatusResponse(listingCall.request(), CALENDARS_RESPONSE));
        service.stop();
        clock.tick();

        assertThat(future).isNotDone();
    }

    /// Stopping fails the requests in flight, and that failure reaches no caller.
    @Test
    void fetchEvents_inFlightWhenTheServiceStops_neverCompletes() {
        answerWith(IcloudCalendarServiceTest::respondAsIcloud);
        service.start();
        Calendar calendar = retrieveCalendars().getFirst();
        List<PendingCall> pendingCalls = stubDeferredCalls(httpClient);
        CompletableFuture<List<CalendarEvent>> events = calendar.fetchEvents(FROM, TO);
        clock.tick();
        PendingCall reportCall = takeOnlyPendingCall(pendingCalls);

        service.stop();
        reportCall.fail(new IOException("Canceled"));
        clock.tick();

        assertThat(events).isNotDone();
    }

    /// A report answered just before the stop is read on the executor after it. The service is found stopped there, so the caller's future never completes.
    @Test
    void fetchEvents_answeredJustBeforeTheServiceStops_neverCompletes() {
        answerWith(IcloudCalendarServiceTest::respondAsIcloud);
        service.start();
        Calendar calendar = retrieveCalendars().getFirst();
        List<PendingCall> pendingCalls = stubDeferredCalls(httpClient);
        CompletableFuture<List<CalendarEvent>> events = calendar.fetchEvents(FROM, TO);
        clock.tick();
        PendingCall reportCall = takeOnlyPendingCall(pendingCalls);

        reportCall.respond(createMultiStatusResponse(reportCall.request(), EVENTS_RESPONSE));
        service.stop();
        clock.tick();

        assertThat(events).isNotDone();
    }

    /// An href opens with the account id, and a calendar's title and its events are the user's own words. None of them reaches the log.
    @Test
    void neitherRetrievalNorFetchLogsPersonalData() {
        answerWith(IcloudCalendarServiceTest::respondAsIcloud);
        service.start();

        List<LogCapture.CapturedLine> lines;
        try (var restClientsLog = LogCapture.capture(RestClients.class, Level.DEBUG);
             var serviceLog = LogCapture.capture(IcloudCalendarService.class, Level.DEBUG);
             var calendarLog = LogCapture.capture(IcloudCalendar.class, Level.DEBUG)) {
            CompletableFuture<List<CalendarEvent>> events = retrieveCalendars().getFirst().fetchEvents(FROM, TO);
            clock.tick();
            assertThat(events).succeedsWithin(Duration.ZERO);
            assertThat(serviceLog.lines()).as("the retrieval's own log line").isNotEmpty();
            lines = Stream.of(restClientsLog, serviceLog, calendarLog).flatMap(capture -> capture.lines().stream()).toList();
        }

        assertThat(lines).isNotEmpty()
                         .flatMap(line -> line.text().toList())
                         .noneMatch(text -> Stream.of(ACCOUNT_ID, CALENDAR_TITLE, "Dentist", "Harley Street").anyMatch(text::contains));
    }

    private static Response respondAsIcloud(Request request) {
        String path = request.url().encodedPath();
        if (WELL_KNOWN_CALDAV_URL.encodedPath().equals(path)) {
            return createMultiStatusResponse(request, HOME_RESPONSE);
        }
        return path.equals(CALENDAR_HREF) && REPORT.equals(request.method())
               ? createMultiStatusResponse(request, EVENTS_RESPONSE)
               : createMultiStatusResponse(request, CALENDARS_RESPONSE);
    }

    private OkHttpClient createUnstubbedHttpClient(Duration timeout) {
        return newUnstubbedService(timeout).createHttpClient();
    }

    private IcloudCalendarService newUnstubbedService(Duration timeout) {
        return new IcloudCalendarService(() -> executor, USERNAME, PASSWORD, timeout, LOG_SUBJECT_ID, new ListenerBackedTaskExceptionHandlerRegistry());
    }

    private static void assertFailsWithStatus(CompletableFuture<?> future, int expectedStatus) {
        assertThat(future).failsWithin(Duration.ZERO)
                          .withThrowableThat()
                          .satisfies(e -> assertThat(HttpResponseException.findIn(e))
                                  .hasValueSatisfying(httpFailure -> assertThat(httpFailure.statusCode()).isEqualTo(expectedStatus)));
    }

    private void answerWith(Function<Request, Response> responder) {
        requests = stubCalls(httpClient, responder);
    }

    private List<Calendar> retrieveCalendars() {
        CompletableFuture<CalendarsResult> future = retrieveCalendarsFuture();
        assertThat(future).succeedsWithin(Duration.ZERO);
        if (future.getNow(null) instanceof Calendars(List<Calendar> calendars)) {
            return calendars;
        }
        throw new AssertionError("expected a calendar list, got " + future.getNow(null));
    }

    private CompletableFuture<CalendarsResult> retrieveCalendarsFuture() {
        CompletableFuture<CalendarsResult> future = service.retrieveCalendars();
        clock.tick();
        return future;
    }
}
