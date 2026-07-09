package net.yudichev.jiotty.connector.google.calendar;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.common.time.calendar.Calendar;
import net.yudichev.jiotty.common.time.calendar.CalendarEvent;
import net.yudichev.jiotty.security.OAuth2TokenManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoogleCalendarServiceTest {
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
        startService((_, url) -> url.contains("/calendars/cal-1/events")
                                 ? ok("""
                                      {"items":[
                                        {"id":"e1","summary":"Meeting","description":"Sync up","location":"Room 5",
                                         "start":{"dateTime":"2026-06-20T10:00:00Z"},"end":{"dateTime":"2026-06-20T11:00:00Z"}},
                                        {"id":"e2","summary":"Holiday","start":{"date":"2026-06-21"},"end":{"date":"2026-06-22"}}
                                      ]}""")
                                 : ok("""
                                      {"items":[{"id":"cal-1","summary":"Home"}],"nextSyncToken":"sync-1"}"""));

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
        startService((_, url) -> {
            if (url.contains("/calendars/cal-1/events")) {
                return url.contains("pageToken=evt-2")
                       ? ok("""
                            {"items":[{"id":"e2","summary":"Second","start":{"dateTime":"2026-06-20T12:00:00Z"},"end":{"dateTime":"2026-06-20T13:00:00Z"}}]}""")
                       : ok("""
                            {"items":[{"id":"e1","summary":"First","start":{"dateTime":"2026-06-20T10:00:00Z"},"end":{"dateTime":"2026-06-20T11:00:00Z"}}],
                             "nextPageToken":"evt-2"}""");
            }
            return ok("""
                      {"items":[{"id":"cal-1","summary":"Home"}],"nextSyncToken":"sync-1"}""");
        });

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
        // the live token is "access-token"; subscribers must never see it — only the opaque authenticated marker
        assertThat(captured).last().isInstanceOfSatisfying(AuthState.Success.class,
                                                           success -> assertThat(success.authInfo()).isNotEqualTo("access-token"));

        tokenManager.setState(new AuthState.PermanentFailure("revoked"));
        clock.tick();
        assertThat(captured).last().isInstanceOfSatisfying(AuthState.PermanentFailure.class,
                                                           failure -> assertThat(failure.description()).isEqualTo("revoked"));
    }

    @Test
    void start_withFreshAuthCode_exchangesItViaTokenManager(@Mock OAuth2TokenManager mockTokenManager) {
        when(mockTokenManager.subscribeToAccessTokenState(any())).thenReturn(Closeable.noop());
        service = new GoogleCalendarService(clock, mockTokenManager, "the-redirect", Optional.of("the-code"), Optional.of("the-verifier"),
                                            Duration.ofSeconds(30), "test-user") {
            @Override
            HttpTransport createHttpTransport() {
                return new MockHttpTransport();
            }
        };
        service.start();

        verify(mockTokenManager).onNewAuthCode("the-code", "the-redirect", Optional.of("the-verifier"));
    }

    @Test
    void retrieveCalendars_apiIoException_failsFuture() {
        startService(transportThrowing(new IOException("connection reset")));

        CompletableFuture<List<Calendar>> future = service.retrieveCalendars();
        clock.tick();
        assertThatThrownBy(future::join)
                .hasRootCauseInstanceOf(IOException.class)
                .hasMessageContaining("Failed to sync Google calendar list");
    }

    @Test
    void retrieveCalendars_nonExpiryApiError_failsFuture() {
        startService((_, _) -> new MockLowLevelHttpResponse().setStatusCode(500).setContentType("application/json").setContent("""
                                                                                                                               {"error":{"code":500,"message":"backend error"}}"""));

        CompletableFuture<List<Calendar>> future = service.retrieveCalendars();
        clock.tick();
        assertThatThrownBy(future::join).hasMessageContaining("Failed to sync Google calendar list");
    }

    @Test
    void retrieveCalendars_permanentAuthError_invalidatesCredentialSoUserIsPromptedToReconnect() {
        // A deleted client project surfaces as a 403 (reason not a rate limit); the credential must be invalidated so the auth state goes to PermanentFailure.
        startService((_, _) -> forbidden("forbidden", "Project #123 has been deleted."));

        CompletableFuture<List<Calendar>> future = service.retrieveCalendars();
        clock.tick();

        assertThatThrownBy(future::join).hasMessageContaining("Failed to sync Google calendar list");
        assertThat(tokenManager.invalidations()).singleElement().asString().contains("403");
    }

    @Test
    void retrieveCalendars_notAuthenticated_makesNoApiCallAndDoesNotInvalidate() {
        // Before the token exchange completes the service has no access token: it must not issue an unauthenticated API call (which Google would reject with
        // 401, and the service would misread as a permanent credential rejection and tear the integration down), but return an empty list, leaving the
        // credential untouched.
        var requestCount = new AtomicInteger();
        tokenManager = new FakeOAuth2TokenManager(new AuthState.TransientFailure("Initialising"));
        service = new GoogleCalendarService(clock, tokenManager, "redirect", Optional.empty(), Optional.empty(), Duration.ofSeconds(30), "test-user") {
            @Override
            HttpTransport createHttpTransport() {
                return new MockHttpTransport() {
                    @Override
                    public LowLevelHttpRequest buildRequest(String method, String url) {
                        requestCount.incrementAndGet();
                        return new MockLowLevelHttpRequest();
                    }
                };
            }
        };
        service.start();
        clock.tick(); // run the token-state callback: the non-Success state leaves the access token unset

        assertThat(retrieveCalendars()).isEmpty();
        assertThat(requestCount).hasValue(0);
        assertThat(tokenManager.invalidations()).isEmpty();
    }

    @Test
    void retrieveCalendars_rateLimit403_isTransient_doesNotInvalidateCredential() {
        startService((_, _) -> forbidden("userRateLimitExceeded", "Rate Limit Exceeded"));

        CompletableFuture<List<Calendar>> future = service.retrieveCalendars();
        clock.tick();

        assertThatThrownBy(future::join).hasMessageContaining("Failed to sync Google calendar list");
        assertThat(tokenManager.invalidations()).isEmpty();
    }

    @Test
    void fetchEvents_skipsEventsMissingStartOrEnd() {
        startService((_, url) -> url.contains("/calendars/cal-1/events")
                                 ? ok("""
                                      {"items":[
                                        {"id":"e0","summary":"No times"},
                                        {"id":"e1","summary":"Timed","start":{"dateTime":"2026-06-20T10:00:00Z"},"end":{"dateTime":"2026-06-20T11:00:00Z"}}
                                      ]}""")
                                 : ok("""
                                      {"items":[{"id":"cal-1","summary":"Home"}],"nextSyncToken":"sync-1"}"""));

        assertThat(fetchEvents(retrieveCalendars().getFirst()))
                .extracting(CalendarEvent::summary)
                .containsExactly("Timed");
    }

    @Test
    void googleCalendar_toString_redactsCalendarName() {
        startService((_, _) -> ok("""
                                  {"items":[{"id":"cal-1","summary":"Private Trip"}],"nextSyncToken":"sync-1"}"""));

        assertThat(retrieveCalendars().getFirst()).asString().contains("cal-1").doesNotContain("Private Trip");
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
        tokenManager = new FakeOAuth2TokenManager(new AuthState.Success("access-token"));
        service = new GoogleCalendarService(clock, tokenManager, "redirect", Optional.empty(), Optional.empty(), Duration.ofSeconds(30), "test-user") {
            @Override
            HttpTransport createHttpTransport() {
                return transport;
            }
        };
        service.start();
        clock.tick(); // run the token-state callback so the access token is in place before any fetch
    }

    private List<Calendar> retrieveCalendars() {
        CompletableFuture<List<Calendar>> future = service.retrieveCalendars();
        clock.tick();
        return future.join();
    }

    private List<CalendarEvent> fetchEvents(Calendar calendar) {
        CompletableFuture<List<CalendarEvent>> future = calendar.fetchEvents(Instant.parse("2026-06-20T00:00:00Z"), Instant.parse("2026-06-22T00:00:00Z"));
        clock.tick();
        return future.join();
    }

    private static MockLowLevelHttpResponse ok(String jsonBody) {
        return new MockLowLevelHttpResponse().setStatusCode(200).setContentType("application/json").setContent(jsonBody);
    }

    private static MockLowLevelHttpResponse gone() {
        return new MockLowLevelHttpResponse().setStatusCode(410).setContentType("application/json").setContent("""
                                                                                                               {"error":{"code":410,"message":"Sync token is no longer valid."}}""");
    }

    private static MockLowLevelHttpResponse forbidden(String reason, String message) {
        return new MockLowLevelHttpResponse().setStatusCode(403).setContentType("application/json").setContent(
                """
                {"error":{"code":403,"message":"%s","errors":[{"domain":"global","reason":"%s","message":"%s"}]}}""".formatted(message, reason, message));
    }
}
