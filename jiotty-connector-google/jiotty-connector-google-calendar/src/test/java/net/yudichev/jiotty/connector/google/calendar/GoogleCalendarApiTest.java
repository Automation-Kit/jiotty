package net.yudichev.jiotty.connector.google.calendar;

import com.google.api.services.calendar.model.CalendarList;
import com.google.api.services.calendar.model.Events;
import net.yudichev.jiotty.common.rest.HttpResponseException;
import net.yudichev.jiotty.common.rest.RestClients;
import net.yudichev.jiotty.common.time.calendar.CalendarAuthorisationException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static net.yudichev.jiotty.common.rest.BearerTokens.BEARER_PREFIX;
import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_GATEWAY_502;
import static net.yudichev.jiotty.common.rest.HttpStatuses.FORBIDDEN_403;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.response;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.stubCalls;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendar.INSUFFICIENT_PERMISSIONS_REASON;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarApi.ORDER_BY_PARAM;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarApi.ORDER_BY_START_TIME;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarApi.PAGE_TOKEN_PARAM;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarApi.SHOW_DELETED_PARAM;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarApi.SINGLE_EVENTS_PARAM;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarApi.SYNC_TOKEN_PARAM;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarApi.TIME_MAX_PARAM;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarApi.TIME_MIN_PARAM;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarService.RATE_LIMIT_EXCEEDED_REASON;
import static net.yudichev.jiotty.connector.google.calendar.GoogleErrorReasons.AUTH_ERROR_REASON;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class GoogleCalendarApiTest {
    private static final String ACCESS_TOKEN = "access-token";
    private static final String SUBJECT = "test-user";
    private static final String CALENDAR_ID = "family@group.calendar.google.com";
    @Mock
    private OkHttpClient httpClient;
    private GoogleCalendarApi api;

    @BeforeEach
    void setUp() {
        api = new GoogleCalendarApi(httpClient, SUBJECT);
    }

    @Test
    void listCalendars_fullListing_followsEachPageTokenAndKeepsTheQuery() {
        List<Request> requests = stubCalls(httpClient, request -> request.url().queryParameter(PAGE_TOKEN_PARAM) == null
                                                                  ? response(request, OK_200, """
                                                                                              {"items":[{"id":"alice@example.com"}],
                                                                                               "nextPageToken":"page-2"}""")
                                                                  : response(request, OK_200, """
                                                                                              {"items":[{"id":"bob@example.com"}],
                                                                                               "nextSyncToken":"sync-1"}"""));
        List<CalendarList> consumedPages = new ArrayList<>();

        assertThat(api.listCalendars(ACCESS_TOKEN, Optional.empty(), consumedPages::add))
                .succeedsWithin(Duration.ZERO)
                .extracting(CalendarList::getNextSyncToken)
                .isEqualTo("sync-1");
        assertThat(consumedPages).hasSize(2);
        assertThat(requests).satisfiesExactly(
                first -> {
                    assertThat(first.url().encodedPath()).isEqualTo("/calendar/v3/users/me/calendarList");
                    assertThat(first.url().queryParameter(SHOW_DELETED_PARAM)).isEqualTo("true");
                    assertThat(first.url().queryParameter(SYNC_TOKEN_PARAM)).isNull();
                    assertThat(first.header(AUTHORIZATION)).isEqualTo(BEARER_PREFIX + ACCESS_TOKEN);
                },
                second -> {
                    assertThat(second.url().queryParameter(PAGE_TOKEN_PARAM)).isEqualTo("page-2");
                    assertThat(second.url().queryParameter(SHOW_DELETED_PARAM)).isEqualTo("true");
                });
    }

    @Test
    void listCalendars_incremental_sendsTheSyncToken() {
        List<Request> requests = stubCalls(httpClient, request -> response(request, OK_200, "{}"));

        assertThat(api.listCalendars(ACCESS_TOKEN, Optional.of("sync-1"), _ -> {})).succeedsWithin(Duration.ZERO);
        assertThat(requests).singleElement().satisfies(request -> assertThat(request.url().queryParameter(SYNC_TOKEN_PARAM)).isEqualTo("sync-1"));
    }

    /// Recurring events come back expanded into their occurrences, in start order, on every page.
    @Test
    void listEvents_requestsExpandedOccurrencesInStartOrderOnEveryPage() {
        Instant from = Instant.parse("2024-02-10T08:00:00Z");
        Instant to = Instant.parse("2024-02-17T08:00:00Z");
        List<Request> requests = stubCalls(httpClient, request -> request.url().queryParameter(PAGE_TOKEN_PARAM) == null
                                                                  ? response(request, OK_200, """
                                                                                              {"items":[{"id":"event-1"}],"nextPageToken":"page-2"}""")
                                                                  : response(request, OK_200, """
                                                                                              {"items":[{"id":"event-2"}]}"""));
        List<Events> consumedPages = new ArrayList<>();

        assertThat(api.listEvents(ACCESS_TOKEN, CALENDAR_ID, api.createEventsLoggableUrl("Fam…"), from, to, consumedPages::add))
                .succeedsWithin(Duration.ZERO);
        assertThat(consumedPages).flatExtracting(Events::getItems).extracting("id").containsExactly("event-1", "event-2");
        assertThat(requests).satisfiesExactly(
                first -> {
                    assertThat(first.url().encodedPath()).isEqualTo("/calendar/v3/calendars/" + CALENDAR_ID + "/events");
                    assertThat(first.url().queryParameter(PAGE_TOKEN_PARAM)).isNull();
                    assertTheEventsQuery(first, from, to);
                    assertThat(first.header(AUTHORIZATION)).isEqualTo(BEARER_PREFIX + ACCESS_TOKEN);
                },
                second -> {
                    assertThat(second.url().queryParameter(PAGE_TOKEN_PARAM)).isEqualTo("page-2");
                    assertTheEventsQuery(second, from, to);
                });
    }

    @Test
    void createEventsLoggableUrl_namesTheSubjectAndTheCalendar() {
        assertThat(api.createEventsLoggableUrl("Hom…").toString(64)).isEqualTo("[" + SUBJECT + "] Google events of calendar Hom…");
    }

    /// Google's error `reason` names no user data, so the failure carries it in place of the withheld body.
    @Test
    void listCalendars_failure_carriesTheTextualErrorReason() {
        stubCalls(httpClient, request -> response(request, FORBIDDEN_403, """
                                                                          {"error":{"code":403,"errors":[{"reason":"%s","message":"Forbidden: rate limit exceeded"}]}}""".formatted(
                RATE_LIMIT_EXCEEDED_REASON)));

        assertThat(api.listCalendars(ACCESS_TOKEN, Optional.empty(), _ -> {}))
                .failsWithin(Duration.ZERO)
                .withThrowableThat()
                .havingCause()
                .isInstanceOfSatisfying(HttpResponseException.class, httpFailure -> assertThat(httpFailure.body()).isEqualTo(RATE_LIMIT_EXCEEDED_REASON));
    }

    /// The failure carries none of a response body that lists no textual `reason`.
    @ParameterizedTest
    @ValueSource(strings = {
            // an error envelope with no reason
            """
            {"error":{"code":403,"message":"Forbidden"}}""",
            """
            {"error":{"errors":[{"reason":42}],"message":"Forbidden"}}""",
            // a proxy's error page
            "<html>Forbidden</html>"})
    void listCalendars_failureWithoutATextualReason_withholdsTheBody(String body) {
        stubCalls(httpClient, request -> response(request, FORBIDDEN_403, body));

        assertThat(api.listCalendars(ACCESS_TOKEN, Optional.empty(), _ -> {}))
                .failsWithin(Duration.ZERO)
                .withThrowableThat()
                .havingCause()
                .isInstanceOfSatisfying(HttpResponseException.class, httpFailure -> assertThat(httpFailure.body()).doesNotContain("Forbidden"));
    }

    @Test
    void reportCredentialRejection_passesTheRejectedTokenOnAndNamesTheResponseAndTheRequest() {
        var httpFailure = new HttpResponseException(UNAUTHORIZED_401, AUTH_ERROR_REASON);
        var rejections = new ArrayList<List<String>>();

        CalendarAuthorisationException failure = GoogleCalendarApi.reportCredentialRejection(httpFailure,
                                                                                             "do the thing",
                                                                                             ACCESS_TOKEN,
                                                                                             (token, rejection) -> rejections.add(List.of(token, rejection)));

        String rejection = "Google Calendar API rejected the credential (HTTP 401 " + AUTH_ERROR_REASON + ") trying to do the thing";
        assertThat(failure).hasMessage(rejection).hasCause(httpFailure);
        assertThat(rejections).containsExactly(List.of(ACCESS_TOKEN, rejection));
    }

    @Test
    void createResponseFailure_namesTheRequestTheStatusAndTheReason() {
        var httpFailure = new HttpResponseException(FORBIDDEN_403, INSUFFICIENT_PERMISSIONS_REASON);

        assertThat(GoogleCalendarApi.errorReason(httpFailure)).hasValue(INSUFFICIENT_PERMISSIONS_REASON);
        assertThat(GoogleCalendarApi.createResponseFailure(httpFailure, "do the thing"))
                .hasMessage("Failed to do the thing: HTTP 403 " + INSUFFICIENT_PERMISSIONS_REASON)
                .hasCause(httpFailure);
    }

    @Test
    void createResponseFailure_bodyWithheld_namesTheStatusAlone() {
        var httpFailure = new HttpResponseException(BAD_GATEWAY_502, RestClients.WITHHELD_BODY);

        assertThat(GoogleCalendarApi.errorReason(httpFailure)).isEmpty();
        assertThat(GoogleCalendarApi.createResponseFailure(httpFailure, "do the thing")).hasMessage("Failed to do the thing: HTTP 502");
    }

    private static void assertTheEventsQuery(Request request, Instant from, Instant to) {
        assertThat(request.url().queryParameter(TIME_MIN_PARAM)).isEqualTo(from.toString());
        assertThat(request.url().queryParameter(TIME_MAX_PARAM)).isEqualTo(to.toString());
        assertThat(request.url().queryParameter(SINGLE_EVENTS_PARAM)).isEqualTo("true");
        assertThat(request.url().queryParameter(ORDER_BY_PARAM)).isEqualTo(ORDER_BY_START_TIME);
    }
}
