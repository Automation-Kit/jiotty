package net.yudichev.jiotty.connector.icloud.calendar;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import net.yudichev.jiotty.common.rest.HttpResponseException;
import net.yudichev.jiotty.connector.icloud.calendar.CalDavClient.CalendarCollection;
import net.yudichev.jiotty.connector.icloud.calendar.CalDavClient.DavProperty;
import net.yudichev.jiotty.connector.icloud.calendar.CalDavClient.DavResponse;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.xml.stream.XMLStreamException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static net.yudichev.jiotty.common.rest.HttpStatuses.SERVICE_UNAVAILABLE_503;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.bodyOf;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.stubCalls;
import static net.yudichev.jiotty.connector.icloud.calendar.CalDavClient.DEPTH_HEADER;
import static net.yudichev.jiotty.connector.icloud.calendar.CalDavClient.DISPLAY_NAME;
import static net.yudichev.jiotty.connector.icloud.calendar.CalDavClient.PROPFIND;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.InstanceOfAssertFactories.list;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CalDavClientTest {
    private static final String BASIC_AUTHORISATION = "Basic dGVzdDp0ZXN0";
    @Mock
    private OkHttpClient httpClient;
    private List<Request> requests = List.of();
    private CalDavClient client;

    @BeforeEach
    void setUp() {
        client = new CalDavClient(httpClient, BASIC_AUTHORISATION, "test-user");
    }

    @Test
    void createEventsLoggableUrl_namesTheSubjectAndTheCalendar() {
        assertThat(CalDavClient.createEventsLoggableUrl("user-1", "Fam…").toString(64)).isEqualTo("[user-1] iCloud events of calendar Fam…");
    }

    @Test
    void discoverCalendarHome_asksTheWellKnownAddressAndResolvesTheHomeItNames() {
        answerWith(request -> createMultiStatusResponse(request, HOME_RESPONSE));

        assertThat(client.discoverCalendarHome()).succeedsWithin(Duration.ZERO).isEqualTo(HOME);
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo(PROPFIND);
            assertThat(request.url()).isEqualTo(WELL_KNOWN_CALDAV_URL);
            assertThat(request.header(DEPTH_HEADER)).isEqualTo("0");
            assertThat(request.header(AUTHORIZATION)).isEqualTo(BASIC_AUTHORISATION);
            assertThat(bodyOf(request)).contains("calendar-home-set");
        });
    }

    @Test
    void discoverCalendarHome_unsuccessful_failsWithTheStatus() {
        answerWith(request -> createErrorResponse(request, SERVICE_UNAVAILABLE_503));

        assertThat(client.discoverCalendarHome()).failsWithin(Duration.ZERO)
                                                 .withThrowableThat()
                                                 .havingCause()
                                                 .isInstanceOfSatisfying(HttpResponseException.class, httpFailure -> assertThat(httpFailure.statusCode())
                                                         .isEqualTo(SERVICE_UNAVAILABLE_503));
    }

    /// The listing names other collections in the home too, and a property the server cannot give is reported under a status of its own.
    @Test
    void listCalendars_returnsOnlyTheCalendarsWithTheirTitles() {
        answerWith(request -> createMultiStatusResponse(request, """
                                                                 <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                                                                   <d:response>
                                                                     <d:href>%3$s</d:href>
                                                                     <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                                                                       <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                                                                     <d:propstat><d:prop><d:displayname/></d:prop>
                                                                       <d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
                                                                   </d:response>
                                                                   <d:response>
                                                                     <d:href>%s</d:href>
                                                                     <d:propstat>
                                                                       <d:prop>
                                                                         <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                                                                         <d:displayname>%s</d:displayname>
                                                                       </d:prop>
                                                                       <d:status>HTTP/1.1 200 OK</d:status>
                                                                     </d:propstat>
                                                                   </d:response>
                                                                   <d:response>
                                                                     <d:href>%3$sinbox/</d:href>
                                                                     <d:propstat>
                                                                       <d:prop>
                                                                         <d:resourcetype><d:collection/><c:schedule-inbox/></d:resourcetype>
                                                                         <d:displayname>Inbox</d:displayname>
                                                                       </d:prop>
                                                                       <d:status>HTTP/1.1 200 OK</d:status>
                                                                     </d:propstat>
                                                                   </d:response>
                                                                 </d:multistatus>""".formatted(CALENDAR_HREF, CALENDAR_TITLE, HOME.encodedPath())));

        assertThat(client.listCalendars(HOME)).succeedsWithin(Duration.ZERO)
                                              .asInstanceOf(list(CalendarCollection.class))
                                              .singleElement()
                                              .satisfies(calendar -> {
                                                  assertThat(calendar.href()).isEqualTo(CALENDAR_HREF);
                                                  assertThat(calendar.url()).isEqualTo(HOME.resolve(CALENDAR_HREF));
                                                  assertThat(calendar.displayName()).isEqualTo(CALENDAR_TITLE);
                                              });
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo(PROPFIND);
            assertThat(request.url()).isEqualTo(HOME);
            assertThat(request.header(DEPTH_HEADER)).isEqualTo("1");
        });
    }

    /// Element names are matched by namespace, so a server declaring CalDAV's as the default namespace reads the same as one using a prefix.
    @Test
    void listCalendars_readsTheNamespacesWhicheverWayTheyAreDeclared() {
        answerWith(request -> createMultiStatusResponse(request, CALENDARS_RESPONSE));

        assertThat(client.listCalendars(HOME)).succeedsWithin(Duration.ZERO)
                                              .asInstanceOf(list(CalendarCollection.class))
                                              .extracting(CalendarCollection::displayName)
                                              .containsExactly(CALENDAR_TITLE);
    }

    @Test
    void fetchCalendarData_asksForTheRangeExpandedAndReturnsEachEventsData() {
        answerWith(request -> createMultiStatusResponse(request, """
                                                                 <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                                                                   <d:response>
                                                                     <d:href>%1$sx/a.ics</d:href>
                                                                     <d:propstat><d:prop><c:calendar-data><![CDATA[BEGIN:VCALENDAR
                                                                 END:VCALENDAR]]></c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                                                                   </d:response>
                                                                   <d:response>
                                                                     <d:href>%1$sx/b.ics</d:href>
                                                                     <d:propstat><d:prop><c:calendar-data>BEGIN:VCALENDAR</c:calendar-data></d:prop>
                                                                       <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                                                                   </d:response>
                                                                 </d:multistatus>""".formatted(HOME.encodedPath())));
        HttpUrl calendarUrl = HOME.resolve(CALENDAR_HREF);

        assertThat(client.fetchCalendarData(calendarUrl, _ -> {}, Instant.parse("2026-06-20T00:00:00Z"), Instant.parse("2026-06-22T00:00:00Z")))
                .succeedsWithin(Duration.ZERO)
                .asInstanceOf(list(String.class))
                .containsExactly("BEGIN:VCALENDAR\nEND:VCALENDAR", "BEGIN:VCALENDAR");
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo(REPORT);
            assertThat(request.url()).isEqualTo(calendarUrl);
            assertThat(request.header(DEPTH_HEADER)).isEqualTo("1");
            assertThat(bodyOf(request)).contains("<c:expand start=\"20260620T000000Z\" end=\"20260622T000000Z\"/>")
                                       .contains("<c:time-range start=\"20260620T000000Z\" end=\"20260622T000000Z\"/>");
        });
    }

    /// [CalDavClient#listCalendars] skips the elements and the propstats whose status it cannot read, and lists only the entries carrying both a
    /// `displayname` and a `resourcetype` that holds `calendar`.
    @Test
    void listCalendars_passesOverWhatItDoesNotRecognise() {
        answerWith(request -> createMultiStatusResponse(request, """
                                                                 <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:x="urn:x">
                                                                   <d:response>
                                                                     <d:href>%1$s</d:href>
                                                                     <d:propstat>
                                                                       <d:prop>
                                                                         <d:resourcetype>
                                                                           <d:collection/><c:calendar><x:extra>text</x:extra></c:calendar>
                                                                         </d:resourcetype>
                                                                         <d:displayname><!-- the user's title -->%2$s</d:displayname>
                                                                       </d:prop>
                                                                       <d:status>HTTP/1.1 200 OK</d:status>
                                                                       <d:responsedescription><x:note/></d:responsedescription>
                                                                     </d:propstat>
                                                                     <d:propstat>
                                                                       <d:prop><d:displayname>Overridden</d:displayname></d:prop><d:status>garbled</d:status>
                                                                     </d:propstat>
                                                                     <d:responsedescription>fine</d:responsedescription>
                                                                   </d:response>
                                                                   <d:response>
                                                                     <d:href>%3$suntitled/</d:href>
                                                                     <d:propstat><d:prop><d:resourcetype><c:calendar/></d:resourcetype></d:prop>
                                                                       <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                                                                   </d:response>
                                                                   <d:response>
                                                                     <d:href>%3$suntyped/</d:href>
                                                                     <d:propstat>
                                                                       <d:prop><d:displayname>Untyped</d:displayname></d:prop>
                                                                       <d:status>HTTP/1.1 200 OK</d:status>
                                                                     </d:propstat>
                                                                   </d:response>
                                                                 </d:multistatus>""".formatted(CALENDAR_HREF, CALENDAR_TITLE, HOME.encodedPath())));

        assertThat(client.listCalendars(HOME)).succeedsWithin(Duration.ZERO)
                                              .asInstanceOf(list(CalendarCollection.class))
                                              .extracting(CalendarCollection::href, CalendarCollection::displayName)
                                              .containsExactly(tuple(CALENDAR_HREF, CALENDAR_TITLE));
    }

    @Test
    void discoverCalendarHome_takesTheFirstHrefOfTheHomeSet() {
        HttpUrl firstHome = HOME.newBuilder().host("icloud.com").build();
        answerWith(request -> createMultiStatusResponse(request, """
                                                                 <multistatus xmlns="DAV:">
                                                                   <response>
                                                                     <href>/</href>
                                                                     <propstat>
                                                                       <prop>
                                                                         <calendar-home-set xmlns="urn:ietf:params:xml:ns:caldav">
                                                                           <href xmlns="DAV:">%s</href>
                                                                           <href xmlns="DAV:">https://p42-caldav.icloud.com/other/</href>
                                                                         </calendar-home-set>
                                                                       </prop>
                                                                       <status>HTTP/1.1 200 OK</status>
                                                                     </propstat>
                                                                   </response>
                                                                 </multistatus>""".formatted(firstHome)));

        assertThat(client.discoverCalendarHome()).succeedsWithin(Duration.ZERO).isEqualTo(firstHome);
    }

    @Test
    void calendarCollection_toStringRedactsTheHrefTheUrlAndTheTitle() {
        assertThat(new CalendarCollection(CALENDAR_HREF, HOME.resolve(CALENDAR_HREF), CALENDAR_TITLE)).asString()
                                                                                                      .doesNotContain(ACCOUNT_ID)
                                                                                                      .doesNotContain(CALENDAR_TITLE);
    }

    @Test
    void davResponse_toStringRedactsTheHrefAndItsPropertiesText() {
        var displayNameProperty = new DavProperty(CALENDAR_TITLE, ImmutableSet.of(), Optional.of(CALENDAR_HREF));
        var response = new DavResponse(CALENDAR_HREF, ImmutableMap.of(DISPLAY_NAME, displayNameProperty));

        assertThat(response).asString()
                            .doesNotContain(ACCOUNT_ID)
                            .doesNotContain(CALENDAR_TITLE);
    }

    @Test
    void davProperty_toStringRedactsItsText_withOrWithoutAnHref() {
        assertThat(new DavProperty(CALENDAR_TITLE, ImmutableSet.of(), Optional.of(CALENDAR_HREF))).asString()
                                                                                                  .doesNotContain(ACCOUNT_ID)
                                                                                                  .doesNotContain(CALENDAR_TITLE);
        assertThat(new DavProperty(CALENDAR_TITLE, ImmutableSet.of(), Optional.empty())).asString().doesNotContain(CALENDAR_TITLE);
    }

    @Test
    void discoverCalendarHome_readsTheFirstResponseOnly() {
        answerWith(request -> createMultiStatusResponse(request, HOME_RESPONSE.replace("</multistatus>", """
                                                                                                           <response><href>/other/</href></response>
                                                                                                         </multistatus>""")));

        assertThat(client.discoverCalendarHome()).succeedsWithin(Duration.ZERO).isEqualTo(HOME);
    }

    @ParameterizedTest
    @MethodSource
    void discoverCalendarHome_unusableResponse_fails(String multistatus, String expectedMessage) {
        answerWith(request -> createMultiStatusResponse(request, multistatus));

        assertThat(client.discoverCalendarHome()).failsWithin(Duration.ZERO)
                                                 .withThrowableThat()
                                                 .havingRootCause()
                                                 .withMessageContaining(expectedMessage);
    }

    static Stream<Arguments> discoverCalendarHome_unusableResponse_fails() {
        return Stream.of(arguments("""
                                   <multistatus xmlns="DAV:"/>""",
                                   "holds no WebDAV response"),
                         arguments("""
                                   <multistatus xmlns="DAV:"><response><href>/</href></response></multistatus>""",
                                   "holds no calendar-home-set href"),
                         arguments("""
                                   <multistatus xmlns="DAV:">
                                     <response>
                                       <href>/</href>
                                       <propstat>
                                         <prop><calendar-home-set xmlns="urn:ietf:params:xml:ns:caldav"/></prop>
                                         <status>HTTP/1.1 200 OK</status>
                                       </propstat>
                                     </response>
                                   </multistatus>""",
                                   "holds no calendar-home-set href"),
                         arguments("""
                                   <multistatus xmlns="DAV:"><response><propstat><status>HTTP/1.1 200 OK</status></propstat></response></multistatus>""",
                                   "holds no href"),
                         arguments("""
                                   <multistatus xmlns="DAV:">
                                     <response>
                                       <href>/</href>
                                       <propstat>
                                         <prop>
                                           <calendar-home-set xmlns="urn:ietf:params:xml:ns:caldav"><href xmlns="DAV:">https://</href></calendar-home-set>
                                         </prop>
                                         <status>HTTP/1.1 200 OK</status>
                                       </propstat>
                                     </response>
                                   </multistatus>""",
                                   "unparseable href"),
                         // the next request would send the account's credentials to a host outside iCloud
                         arguments("""
                                   <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                                     <d:response>
                                       <d:href>/</d:href>
                                       <d:propstat>
                                         <d:prop><c:calendar-home-set><d:href>https://caldav.example.com/calendars/</d:href></c:calendar-home-set></d:prop>
                                         <d:status>HTTP/1.1 200 OK</d:status>
                                       </d:propstat>
                                     </d:response>
                                   </d:multistatus>""",
                                   "not an iCloud HTTPS URL"));
    }

    @Test
    void fetchCalendarData_skipsAnEntryCarryingNoData() {
        answerWith(request -> createMultiStatusResponse(request, """
                                                                 <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                                                                   <d:response>
                                                                     <d:href>%sx/gone.ics</d:href>
                                                                     <d:propstat><d:prop><c:calendar-data/></d:prop>
                                                                       <d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
                                                                   </d:response>
                                                                 </d:multistatus>""".formatted(HOME.encodedPath())));

        assertThat(client.fetchCalendarData(HOME.resolve(CALENDAR_HREF), _ -> {}, Instant.EPOCH, Instant.EPOCH))
                .succeedsWithin(Duration.ZERO)
                .asInstanceOf(list(String.class))
                .isEmpty();
    }

    /// An entity defined by the response could read a local file into what the parser returns.
    @Test
    void aResponseDeclaringADocumentTypeIsRefused() {
        answerWith(request -> createMultiStatusResponse(request, """
                                                                 <?xml version="1.0"?>
                                                                 <!DOCTYPE multistatus [<!ENTITY secret SYSTEM "file:///etc/hosts">]>
                                                                 <multistatus xmlns="DAV:">
                                                                   <response>
                                                                     <href>/</href>
                                                                     <propstat>
                                                                       <prop>
                                                                         <calendar-home-set xmlns="urn:ietf:params:xml:ns:caldav">
                                                                           <href xmlns="DAV:">&secret;</href>
                                                                         </calendar-home-set>
                                                                       </prop>
                                                                       <status>HTTP/1.1 200 OK</status>
                                                                     </propstat>
                                                                   </response>
                                                                 </multistatus>"""));

        assertThat(client.discoverCalendarHome()).failsWithin(Duration.ZERO)
                                                 .withThrowableThat()
                                                 .withRootCauseInstanceOf(XMLStreamException.class);
    }

    @Test
    void aCalendarOffIcloudOrOverPlainHttpIsNeverRequested() {
        assertThatThrownBy(() -> client.fetchCalendarData(HOME.newBuilder().scheme("http").build(),
                                                          _ -> {},
                                                          Instant.EPOCH,
                                                          Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.fetchCalendarData(HOME.newBuilder().host("caldav.example.com").build(),
                                                          _ -> {},
                                                          Instant.EPOCH,
                                                          Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        verify(httpClient, never()).newCall(any());
    }

    private void answerWith(Function<Request, Response> responder) {
        requests = stubCalls(httpClient, responder);
    }
}
