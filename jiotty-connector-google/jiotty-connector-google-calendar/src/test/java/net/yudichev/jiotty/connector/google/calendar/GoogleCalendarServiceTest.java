package net.yudichev.jiotty.connector.google.calendar;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import net.yudichev.jiotty.common.async.ListenerBackedTaskExceptionHandlerRegistry;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.MutableReference;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.common.time.calendar.Calendar;
import net.yudichev.jiotty.common.time.calendar.CalendarAuthorisationException;
import net.yudichev.jiotty.common.time.calendar.CalendarEvent;
import net.yudichev.jiotty.common.time.calendar.CalendarService.CalendarsResult;
import net.yudichev.jiotty.common.time.calendar.CalendarService.CalendarsResult.Calendars;
import net.yudichev.jiotty.common.time.calendar.CalendarService.CalendarsResult.NotYetAuthenticated;
import net.yudichev.jiotty.security.OAuth2TokenManager;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.google.common.base.Throwables.getStackTraceAsString;
import static net.yudichev.jiotty.common.rest.HttpStatuses.FORBIDDEN_403;
import static net.yudichev.jiotty.common.rest.HttpStatuses.GONE_410;
import static net.yudichev.jiotty.common.rest.HttpStatuses.INTERNAL_SERVER_ERROR_500;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NOT_FOUND_404;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarRequests.INSUFFICIENT_PERMISSIONS_REASON;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarService.CALENDAR_LIST_SYNC;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarService.RATE_LIMIT_EXCEEDED_REASON;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarService.USER_RATE_LIMIT_EXCEEDED_REASON;
import static net.yudichev.jiotty.connector.google.calendar.GoogleErrorReasons.AUTH_ERROR_REASON;
import static net.yudichev.jiotty.connector.google.calendar.GoogleErrorReasons.FORBIDDEN_FOR_NON_ORGANIZER_REASON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoogleCalendarServiceTest {
    private static final String ACCESS_TOKEN = "access-token";
    /// A pseudonymous calendar id, distinctive enough that its absence from a failure is checkable.
    private static final String CALENDAR_ID = "calendar-7f3a9c";
    private final ProgrammableClock clock = new ProgrammableClock();
    private FakeOAuth2TokenManager tokenManager;
    private GoogleCalendarService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.stop();
        }
    }

    @Test
    void retrieveCalendars_fullSync_returnsAllCalendarsAndPrefersSummaryOverride() {
        startService((_, _) -> ok("""
                                  {"items":[
                                    {"id":"cal-1","summary":"Home"},
                                    {"id":"cal-2","summary":"Work","summaryOverride":"Work (mine)"}
                                  ],"nextSyncToken":"sync-1"}"""));

        assertThat(retrieveCalendars())
                .extracting(Calendar::id, Calendar::name)
                .containsExactly(tuple("cal-1", "Home"), tuple("cal-2", "Work (mine)"));
    }

    @Test
    void retrieveCalendars_fullSync_paginates() {
        startService((_, url) -> url.contains("pageToken=page-2")
                                 ? ok("""
                                      {"items":[{"id":"cal-2","summary":"Work"}],"nextSyncToken":"sync-1"}""")
                                 : ok("""
                                      {"items":[{"id":"cal-1","summary":"Home"}],"nextPageToken":"page-2"}"""));

        assertThat(retrieveCalendars()).extracting(Calendar::name).containsExactly("Home", "Work");
    }

    @Test
    void retrieveCalendars_incrementalSync_appliesUpdatesAndRemovals() {
        startService((_, url) -> url.contains("syncToken=sync-1")
                                 ? ok("""
                                      {"items":[{"id":"cal-1","summary":"Home renamed"},{"id":"cal-2","deleted":true}],"nextSyncToken":"sync-2"}""")
                                 : ok("""
                                      {"items":[{"id":"cal-1","summary":"Home"},{"id":"cal-2","summary":"Work"}],"nextSyncToken":"sync-1"}"""));

        assertThat(retrieveCalendars()).extracting(Calendar::name).containsExactly("Home", "Work");
        // second refresh sends sync-1, gets a delta: cal-1 renamed, cal-2 removed
        assertThat(retrieveCalendars())
                .extracting(Calendar::id, Calendar::name)
                .containsExactly(tuple("cal-1", "Home renamed"));
    }

    @Test
    void retrieveCalendars_syncTokenExpired_fullResync() {
        startService((_, url) -> url.contains("syncToken=sync-1")
                                 ? gone()
                                 : ok("""
                                      {"items":[{"id":"cal-1","summary":"Home"}],"nextSyncToken":"sync-1"}"""));

        assertThat(retrieveCalendars()).extracting(Calendar::name).containsExactly("Home");
        // the incremental refresh sends sync-1 and gets 410; the service drops the token and full-resyncs, recovering the list
        assertThat(retrieveCalendars()).extracting(Calendar::name).containsExactly("Home");
    }

    @Test
    void fetchEvents_mapsTimedAndAllDayEvents() {
        startServiceWithEventsAnswering(_ -> ok("""
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
        startServiceWithEventsAnswering(url -> url.contains("pageToken=evt-2")
                                               ? ok("""
                                                    {"items":[{"id":"e2","summary":"Second",
                                                      "start":{"dateTime":"2026-06-20T12:00:00Z"},"end":{"dateTime":"2026-06-20T13:00:00Z"}}]}""")
                                               : ok("""
                                                    {"items":[{"id":"e1","summary":"First",
                                                      "start":{"dateTime":"2026-06-20T10:00:00Z"},"end":{"dateTime":"2026-06-20T11:00:00Z"}}],
                                                     "nextPageToken":"evt-2"}"""));

        assertThat(fetchEvents(retrieveCalendars().getFirst()))
                .extracting(CalendarEvent::summary)
                .containsExactly("First", "Second");
    }

    @Test
    void subscribeToAuthState_hidesAccessTokenAndForwardsFailure() {
        startService((_, _) -> ok("""
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
        service = new GoogleCalendarService(clock,
                                            mockTokenManager,
                                            "the-redirect",
                                            Optional.of("the-code"),
                                            Optional.of("the-verifier"),
                                            Duration.ofSeconds(30),
                                            "test-user",
                                            new ListenerBackedTaskExceptionHandlerRegistry()) {
            @Override
            HttpTransport createHttpTransport() {
                return new MockHttpTransport();
            }
        };
        service.start();

        verify(mockTokenManager).onNewAuthCode("the-code", "the-redirect", Optional.of("the-verifier"));
    }

    @Test
    void disconnect_revokesTheCredential() {
        startService((_, _) -> ok("""
                                  {"items":[]}"""));

        assertThat(service.disconnect()).succeedsWithin(Duration.ZERO);
        assertThat(tokenManager.revocationCount()).isEqualTo(1);
    }

    @Test
    void retrieveCalendars_apiIoException_failsFuture() {
        startService(transportThrowing(new IOException("connection reset")));

        CompletableFuture<CalendarsResult> future = service.retrieveCalendars();
        clock.tick();
        assertThat(future).failsWithin(Duration.ZERO)
                          .withThrowableOfType(ExecutionException.class)
                          .withRootCauseInstanceOf(IOException.class)
                          .withMessageContaining("Failed to " + CALENDAR_LIST_SYNC);
    }

    /// A server error and a rate limit are transient, so the failure leaves the credential valid.
    ///
    /// @param reason the error reason the response lists, or `null` for a response listing none
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource
    void retrieveCalendars_transientApiError_failsWithoutInvalidatingTheCredential(int statusCode, @Nullable String reason) {
        startService((_, _) -> createErrorResponse(statusCode, reason, "error"));

        CompletableFuture<CalendarsResult> future = service.retrieveCalendars();
        clock.tick();

        assertThat(future).failsWithin(Duration.ZERO)
                          .withThrowableOfType(ExecutionException.class)
                          .havingCause()
                          .isNotInstanceOf(CalendarAuthorisationException.class)
                          .withMessageContaining("Failed to " + CALENDAR_LIST_SYNC)
                          .withNoCause();
        assertThat(tokenManager.invalidations()).isEmpty();
    }

    static Stream<Arguments> retrieveCalendars_transientApiError_failsWithoutInvalidatingTheCredential() {
        return Stream.of(arguments(INTERNAL_SERVER_ERROR_500, null),
                         arguments(FORBIDDEN_403, USER_RATE_LIMIT_EXCEEDED_REASON));
    }

    /// A deleted client project surfaces as a forbidden response whose reason is not a rate limit, and a revoked grant as an unauthorised one; a forbidden
    /// response listing no reason counts as permanent too. Each invalidates the access token the request carried and fails as an authorisation failure.
    ///
    /// @param reason the error reason the response lists, or `null` for a response listing none
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource
    void retrieveCalendars_permanentAuthError_invalidatesCredentialSoUserIsPromptedToReconnect(int statusCode, @Nullable String reason) {
        startService((_, _) -> createErrorResponse(statusCode, reason, "rejected"));

        CompletableFuture<CalendarsResult> future = service.retrieveCalendars();
        clock.tick();

        assertThat(future).failsWithin(Duration.ZERO)
                          .withThrowableOfType(ExecutionException.class)
                          .havingCause()
                          .isInstanceOf(CalendarAuthorisationException.class)
                          .withMessageContaining("trying to " + CALENDAR_LIST_SYNC)
                          .withNoCause();
        assertThat(tokenManager.invalidations()).singleElement().asString().contains("(HTTP " + statusCode + (reason == null ? "" : " " + reason) + ")");
        assertThat(tokenManager.invalidatedAccessTokens()).containsExactly(ACCESS_TOKEN);
    }

    static Stream<Arguments> retrieveCalendars_permanentAuthError_invalidatesCredentialSoUserIsPromptedToReconnect() {
        return Stream.of(arguments(FORBIDDEN_403, "forbidden"),
                         arguments(FORBIDDEN_403, null),
                         arguments(UNAUTHORIZED_401, AUTH_ERROR_REASON));
    }

    @Test
    void retrieveCalendars_notAuthenticated_reportsNotYetAuthenticatedWithoutApiCall() {
        // Before the token exchange completes the service has no access token: it must not issue an unauthenticated API call (which Google would reject with
        // 401, and the service would misread as a permanent credential rejection and tear the integration down), but report the not-yet-authenticated state —
        // never a successful empty list, which callers would trust as "the account has no calendars" — leaving the credential untouched.
        var requestCount = new MutableReference<>(0);
        tokenManager = new FakeOAuth2TokenManager(new AuthState.TransientFailure("Initialising"));
        service = new GoogleCalendarService(clock,
                                            tokenManager,
                                            "redirect",
                                            Optional.empty(),
                                            Optional.empty(),
                                            Duration.ofSeconds(30),
                                            "test-user",
                                            new ListenerBackedTaskExceptionHandlerRegistry()) {
            @Override
            HttpTransport createHttpTransport() {
                return new MockHttpTransport() {
                    @Override
                    public LowLevelHttpRequest buildRequest(String method, String url) {
                        requestCount.set(requestCount.get() + 1);
                        return new MockLowLevelHttpRequest();
                    }
                };
            }
        };
        service.start();
        clock.tick(); // run the token-state callback: the non-Success state leaves the access token unset

        CompletableFuture<CalendarsResult> future = service.retrieveCalendars();
        clock.tick();
        assertThat(future).succeedsWithin(Duration.ZERO).isSameAs(NotYetAuthenticated.INSTANCE);
        assertThat(requestCount.get()).isZero();
        assertThat(tokenManager.invalidations()).isEmpty();
    }

    @Test
    void fetchEvents_skipsEventsMissingStartOrEnd() {
        startServiceWithEventsAnswering(_ -> ok("""
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
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource
    void fetchEvents_credentialRejected_invalidatesCredentialAndFailsAsAnAuthorisationFailure(int statusCode, String reason) {
        startServiceWithEventsAnswering(_ -> createErrorResponse(statusCode, reason, "rejected"));

        assertThat(fetchEventsFuture(retrieveCalendars().getFirst())).failsWithin(Duration.ZERO)
                                                                     .withThrowableOfType(ExecutionException.class)
                                                                     .satisfies(GoogleCalendarServiceTest::assertNamesNoCalendarId)
                                                                     .havingCause()
                                                                     .isInstanceOf(CalendarAuthorisationException.class)
                                                                     .withNoCause();
        assertThat(tokenManager.invalidations()).singleElement().asString().contains(String.valueOf(statusCode), reason);
        assertThat(tokenManager.invalidatedAccessTokens()).containsExactly(ACCESS_TOKEN);
    }

    static Stream<Arguments> fetchEvents_credentialRejected_invalidatesCredentialAndFailsAsAnAuthorisationFailure() {
        return Stream.of(arguments(FORBIDDEN_403, INSUFFICIENT_PERMISSIONS_REASON),
                         arguments(UNAUTHORIZED_401, AUTH_ERROR_REASON));
    }

    /// A quota limit or a failure confined to one calendar leaves in place the credential every calendar is read with, and the failure names no calendar id.
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource
    void fetchEvents_otherErrors_failWithoutInvalidatingTheCredential(int statusCode, String reason) {
        startServiceWithEventsAnswering(_ -> createErrorResponse(statusCode, reason, "error"));

        assertThat(fetchEventsFuture(retrieveCalendars().getFirst())).failsWithin(Duration.ZERO)
                                                                     .withThrowableOfType(ExecutionException.class)
                                                                     .satisfies(GoogleCalendarServiceTest::assertNamesNoCalendarId)
                                                                     .havingCause()
                                                                     .isNotInstanceOf(CalendarAuthorisationException.class)
                                                                     .withMessageContaining("Failed to fetch events")
                                                                     .withNoCause();
        assertThat(tokenManager.invalidations()).isEmpty();
    }

    static Stream<Arguments> fetchEvents_otherErrors_failWithoutInvalidatingTheCredential() {
        return Stream.of(arguments(FORBIDDEN_403, RATE_LIMIT_EXCEEDED_REASON),
                         arguments(FORBIDDEN_403, FORBIDDEN_FOR_NON_ORGANIZER_REASON),
                         arguments(NOT_FOUND_404, "notFound"));
    }

    @Test
    void googleCalendar_toString_redactsIdAndName() {
        // A Google calendar id is the account's email address, so it is redacted alongside the title.
        startService((_, _) -> ok("""
                                  {"items":[{"id":"owner@example.com","summary":"Private Trip"}],"nextSyncToken":"sync-1"}"""));

        assertThat(retrieveCalendars().getFirst()).asString()
                                                  .doesNotContain("owner@example.com")
                                                  .doesNotContain("Private Trip")
                                                  .contains("own…")
                                                  .contains("Pri…");
    }

    private static HttpTransport transportThrowing(IOException error) {
        return new MockHttpTransport() {
            @Override
            public LowLevelHttpRequest buildRequest(String method, String url) {
                return new MockLowLevelHttpRequest() {
                    @Override
                    public LowLevelHttpResponse execute() throws IOException {
                        throw error;
                    }
                };
            }
        };
    }

    /// Serves one calendar, [#CALENDAR_ID], and responds to each request for its events with what `eventsResponder` returns for the request URL.
    private void startServiceWithEventsAnswering(Function<String, MockLowLevelHttpResponse> eventsResponder) {
        startService((_, url) -> url.contains("/events")
                                 ? eventsResponder.apply(url)
                                 : ok("""
                                      {"items":[{"id":"%s","summary":"Home"}],"nextSyncToken":"sync-1"}""".formatted(CALENDAR_ID)));
    }

    private void startService(BiFunction<String, String, MockLowLevelHttpResponse> responder) {
        startService(new MockHttpTransport() {
            @Override
            public LowLevelHttpRequest buildRequest(String method, String url) {
                return new MockLowLevelHttpRequest() {
                    @Override
                    public LowLevelHttpResponse execute() {
                        return responder.apply(method, url);
                    }
                };
            }
        });
    }

    private void startService(HttpTransport transport) {
        tokenManager = new FakeOAuth2TokenManager(new AuthState.Success(ACCESS_TOKEN));
        service = new GoogleCalendarService(clock,
                                            tokenManager,
                                            "redirect",
                                            Optional.empty(),
                                            Optional.empty(),
                                            Duration.ofSeconds(30),
                                            "test-user",
                                            new ListenerBackedTaskExceptionHandlerRegistry()) {
            @Override
            HttpTransport createHttpTransport() {
                return transport;
            }
        };
        service.start();
        clock.tick(); // run the token-state callback so the access token is in place before any fetch
    }

    private List<Calendar> retrieveCalendars() {
        CompletableFuture<CalendarsResult> future = service.retrieveCalendars();
        clock.tick();
        return switch (assertThat(future).succeedsWithin(Duration.ZERO).actual()) {
            case Calendars(List<Calendar> calendars) -> calendars;
            case NotYetAuthenticated _ -> throw new AssertionError("expected a calendar list, but the service reported it is not yet authenticated");
        };
    }

    private List<CalendarEvent> fetchEvents(Calendar calendar) {
        return assertThat(fetchEventsFuture(calendar)).succeedsWithin(Duration.ZERO).actual();
    }

    private CompletableFuture<List<CalendarEvent>> fetchEventsFuture(Calendar calendar) {
        CompletableFuture<List<CalendarEvent>> future = calendar.fetchEvents(Instant.parse("2026-06-20T00:00:00Z"), Instant.parse("2026-06-22T00:00:00Z"));
        clock.tick();
        return future;
    }

    private static MockLowLevelHttpResponse ok(String jsonBody) {
        return new MockLowLevelHttpResponse().setStatusCode(OK_200).setContentType("application/json").setContent(jsonBody);
    }

    private static MockLowLevelHttpResponse gone() {
        return new MockLowLevelHttpResponse().setStatusCode(GONE_410)
                                             .setContentType("application/json")
                                             .setContent("""
                                                         {"error":{"code":410,"message":"Sync token is no longer valid."}}""");
    }

    /// @param reason the reason of the one error the response lists, or `null` for a response listing none
    private static MockLowLevelHttpResponse createErrorResponse(int statusCode, @Nullable String reason, String message) {
        String errors = reason == null ? "" : """
                                              ,"errors":[{"domain":"global","reason":"%s","message":"%s"}]""".formatted(reason, message);
        return new MockLowLevelHttpResponse().setStatusCode(statusCode).setContentType("application/json").setContent("""
                                                                                                                      {"error":{"code":%d,"message":"%s"%s}}""".formatted(
                statusCode,
                message,
                errors));
    }

    /// Asserts that `failure` and its causes never name [#CALENDAR_ID]: the failure is logged, and a real calendar id is the user's email address.
    private static void assertNamesNoCalendarId(Throwable failure) {
        assertThat(getStackTraceAsString(failure)).doesNotContain(CALENDAR_ID);
    }
}
