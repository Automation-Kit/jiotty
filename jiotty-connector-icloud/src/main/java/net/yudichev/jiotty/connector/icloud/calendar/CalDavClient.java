package net.yudichev.jiotty.connector.icloud.calendar;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.HttpHeaders;
import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.MutableReference;
import net.yudichev.jiotty.common.lang.StringFormattable;
import net.yudichev.jiotty.common.rest.HttpStatuses;
import net.yudichev.jiotty.common.rest.UnsuccessfulResponseDetailExtractor;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.jspecify.annotations.Nullable;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static javax.xml.stream.XMLStreamConstants.CHARACTERS;
import static javax.xml.stream.XMLStreamConstants.END_ELEMENT;
import static javax.xml.stream.XMLStreamConstants.START_ELEMENT;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static net.yudichev.jiotty.common.rest.RestClients.callSuppressingResponseBody;
import static net.yudichev.jiotty.common.security.LogRedaction.appendRedacted;

/// The three CalDAV exchanges the iCloud connector makes: finding the account's calendar home, listing the calendars in it, and reading one calendar's
/// events. Log lines and failures name each request by its loggable URL and carry no response body, because hrefs open with the account id and events are
/// personal data.
final class CalDavClient {
    @VisibleForTesting
    static final HttpUrl WELL_KNOWN_CALDAV_URL = HttpUrl.get("https://caldav.icloud.com/.well-known/caldav");
    @VisibleForTesting
    static final MediaType XML = MediaType.get("application/xml; charset=utf-8");
    /// The WebDAV request header that sets how deep below the target a {@value #PROPFIND} or {@value #REPORT} reaches.
    @VisibleForTesting
    static final String DEPTH_HEADER = "Depth";
    @VisibleForTesting
    static final String PROPFIND = "PROPFIND";
    @VisibleForTesting
    static final String REPORT = "REPORT";
    private static final String DAV_NAMESPACE = "DAV:";
    @VisibleForTesting
    static final QName DISPLAY_NAME = new QName(DAV_NAMESPACE, "displayname");
    private static final String CALDAV_NAMESPACE = "urn:ietf:params:xml:ns:caldav";
    private static final QName RESPONSE = new QName(DAV_NAMESPACE, "response");
    private static final QName HREF = new QName(DAV_NAMESPACE, "href");
    private static final QName PROPSTAT = new QName(DAV_NAMESPACE, "propstat");
    private static final QName PROP = new QName(DAV_NAMESPACE, "prop");
    private static final QName STATUS = new QName(DAV_NAMESPACE, "status");
    private static final QName RESOURCE_TYPE = new QName(DAV_NAMESPACE, "resourcetype");
    private static final QName CALENDAR_HOME_SET = new QName(CALDAV_NAMESPACE, "calendar-home-set");
    private static final QName CALENDAR = new QName(CALDAV_NAMESPACE, "calendar");
    private static final QName CALENDAR_DATA = new QName(CALDAV_NAMESPACE, "calendar-data");
    private static final String OK_STATUS_CODE = Integer.toString(OK_200);
    private static final String DAV_PREFIX_DECLARATION = "xmlns:d=\"" + DAV_NAMESPACE + '"';
    private static final String PREFIX_DECLARATIONS = DAV_PREFIX_DECLARATION + " xmlns:c=\"" + CALDAV_NAMESPACE + '"';
    private static final RequestBody CALENDAR_HOME_REQUEST_BODY = RequestBody.create("""
                                                                                     <?xml version="1.0" encoding="UTF-8"?>
                                                                                     <d:propfind %s>
                                                                                       <d:prop><c:calendar-home-set/></d:prop>
                                                                                     </d:propfind>""".formatted(PREFIX_DECLARATIONS), XML);
    private static final RequestBody CALENDARS_REQUEST_BODY = RequestBody.create("""
                                                                                 <?xml version="1.0" encoding="UTF-8"?>
                                                                                 <d:propfind %s>
                                                                                   <d:prop><d:resourcetype/><d:displayname/></d:prop>
                                                                                 </d:propfind>""".formatted(DAV_PREFIX_DECLARATION), XML);
    private static final DateTimeFormatter CALDAV_UTC_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    /// Rejects a document type declaration, and with it every entity a response could define — the defence against XML external entities.
    private static final XMLInputFactory XML_INPUT_FACTORY = createXmlInputFactory();

    private final OkHttpClient httpClient;
    private final String authorisation;
    private final StringFormattable calendarHomeLoggableUrl;
    private final StringFormattable calendarListLoggableUrl;

    /// @param authorisation the {@value HttpHeaders#AUTHORIZATION} header value every request carries
    public CalDavClient(OkHttpClient httpClient, String authorisation, String logSubjectId) {
        this.httpClient = checkNotNull(httpClient);
        this.authorisation = checkNotNull(authorisation);
        calendarHomeLoggableUrl = createLoggableUrl(logSubjectId, "iCloud calendar home");
        calendarListLoggableUrl = createLoggableUrl(logSubjectId, "iCloud calendar list");
    }

    public static StringFormattable createEventsLoggableUrl(String logSubjectId, String redactedCalendarName) {
        return createLoggableUrl(logSubjectId, "iCloud events of calendar " + redactedCalendarName);
    }

    /// @param description what the request reads; must not hold personal data
    /// @return a loggable URL rendering as `[logSubjectId] description`
    private static StringFormattable createLoggableUrl(String logSubjectId, String description) {
        checkNotNull(logSubjectId);
        return appendable -> {
            Append.to(appendable, '[');
            Append.to(appendable, logSubjectId);
            Append.to(appendable, "] ");
            Append.to(appendable, description);
        };
    }

    /// @return the account's calendar home, which may be on another iCloud host than the one asked
    public CompletableFuture<HttpUrl> discoverCalendarHome() {
        return call(PROPFIND, WELL_KNOWN_CALDAV_URL, 0, CALENDAR_HOME_REQUEST_BODY, calendarHomeLoggableUrl, body -> {
            MutableReference<@Nullable DavResponse> firstResponse = new MutableReference<>();
            parseMultistatus(body, response -> {
                if (firstResponse.get() == null) {
                    firstResponse.set(response);
                }
            });
            DavResponse response = firstResponse.get();
            checkState(response != null, "the calendar home response holds no WebDAV response");
            return Optional.ofNullable(response.propertiesByName().get(CALENDAR_HOME_SET))
                           .flatMap(DavProperty::href)
                           .map(homeHref -> resolve(WELL_KNOWN_CALDAV_URL, homeHref))
                           .orElseThrow(() -> new IllegalStateException("the calendar home response holds no calendar-home-set href"));
        });
    }

    /// @return the calendars in `calendarHome`, in the order the server lists them
    public CompletableFuture<List<CalendarCollection>> listCalendars(HttpUrl calendarHome) {
        return call(PROPFIND, calendarHome, 1, CALENDARS_REQUEST_BODY, calendarListLoggableUrl, body -> {
            var calendars = ImmutableList.<CalendarCollection>builder();
            parseMultistatus(body, response -> {
                DavProperty displayName = response.propertiesByName().get(DISPLAY_NAME);
                DavProperty resourceType = response.propertiesByName().get(RESOURCE_TYPE);
                if (displayName != null && resourceType != null && resourceType.children().contains(CALENDAR)) {
                    calendars.add(new CalendarCollection(response.href(), resolve(calendarHome, response.href()), displayName.text()));
                }
            });
            return calendars.build();
        });
    }

    /// @param loggableUrl names the calendar in log lines and failures, from [#createEventsLoggableUrl]
    /// @return the iCalendar text of each event overlapping `[from, to)`, recurring ones expanded into their occurrences
    public CompletableFuture<List<String>> fetchCalendarData(HttpUrl calendarUrl, StringFormattable loggableUrl, Instant from, Instant to) {
        String start = CALDAV_UTC_TIME_FORMATTER.format(from);
        String end = CALDAV_UTC_TIME_FORMATTER.format(to);
        // the placeholders are the prefix declarations, then the range's start and end, twice
        RequestBody requestBody = RequestBody.create("""
                                                     <?xml version="1.0" encoding="UTF-8"?>
                                                     <c:calendar-query %s>
                                                       <d:prop><c:calendar-data><c:expand start="%s" end="%s"/></c:calendar-data></d:prop>
                                                       <c:filter>
                                                         <c:comp-filter name="VCALENDAR">
                                                           <c:comp-filter name="VEVENT"><c:time-range start="%s" end="%s"/></c:comp-filter>
                                                         </c:comp-filter>
                                                       </c:filter>
                                                     </c:calendar-query>""".formatted(PREFIX_DECLARATIONS, start, end, start, end), XML);
        return call(REPORT, calendarUrl, 1, requestBody, loggableUrl, body -> {
            var calendarData = ImmutableList.<String>builder();
            parseMultistatus(body, response -> {
                DavProperty data = response.propertiesByName().get(CALENDAR_DATA);
                if (data != null) {
                    calendarData.add(data.text());
                }
            });
            return calendarData.build();
        });
    }

    private <T> CompletableFuture<T> call(String method,
                                          HttpUrl url,
                                          int depth,
                                          RequestBody requestBody,
                                          StringFormattable loggableUrl,
                                          Function<String, ? extends T> parser) {
        Request request = new Request.Builder().url(checkIcloud(url))
                                               .header(AUTHORIZATION, authorisation)
                                               .header(DEPTH_HEADER, Integer.toString(depth))
                                               .method(method, requestBody)
                                               .build();
        // the callers retry the whole exchange
        return callSuppressingResponseBody(httpClient.newCall(request), parser, 0, loggableUrl, UnsuccessfulResponseDetailExtractor.NONE);
    }

    /// Resolves a server-supplied href, which is usually an absolute path, and refuses one leading off iCloud — the account's credentials would follow it.
    private static HttpUrl resolve(HttpUrl base, String href) {
        HttpUrl url = base.resolve(href);
        checkArgument(url != null, "unparseable href in a CalDAV response");
        return checkIcloud(url);
    }

    private static HttpUrl checkIcloud(HttpUrl url) {
        String host = url.host();
        checkArgument(url.isHttps() && ("icloud.com".equals(host) || host.endsWith(".icloud.com")), "not an iCloud HTTPS URL: host %s", host);
        return url;
    }

    /// Reads a {@value HttpStatuses#MULTI_STATUS_207} Multi-Status body, handing `responseConsumer` each `response` with the properties of its `propstat`s
    /// whose status is {@value HttpStatuses#OK_200}.
    private static void parseMultistatus(String body, Consumer<DavResponse> responseConsumer) {
        asUnchecked(() -> {
            XMLStreamReader reader = XML_INPUT_FACTORY.createXMLStreamReader(new StringReader(body));
            while (reader.hasNext()) {
                if (reader.next() == START_ELEMENT && reader.getName().equals(RESPONSE)) {
                    responseConsumer.accept(readResponse(reader));
                }
            }
        });
    }

    private static DavResponse readResponse(XMLStreamReader reader) throws XMLStreamException {
        String href = null;
        var propertiesByNameBuilder = ImmutableMap.<QName, DavProperty>builder();
        while (reader.nextTag() == START_ELEMENT) {
            QName name = reader.getName();
            if (name.equals(HREF)) {
                href = reader.getElementText().strip();
            } else if (name.equals(PROPSTAT)) {
                readPropstat(reader, propertiesByNameBuilder);
            } else {
                skipElement(reader);
            }
        }
        checkState(href != null, "a WebDAV response holds no href");
        return new DavResponse(href, propertiesByNameBuilder.buildKeepingLast());
    }

    private static void readPropstat(XMLStreamReader reader, ImmutableMap.Builder<QName, DavProperty> propertiesByNameBuilder) throws XMLStreamException {
        // a propstat's status may follow its prop, so propstatPropertiesByName holds the properties until isOk has read the status
        Map<QName, DavProperty> propstatPropertiesByName = ImmutableMap.of();
        boolean ok = false;
        while (reader.nextTag() == START_ELEMENT) {
            QName name = reader.getName();
            if (name.equals(PROP)) {
                propstatPropertiesByName = readProp(reader);
            } else if (name.equals(STATUS)) {
                ok = isOk(reader.getElementText());
            } else {
                skipElement(reader);
            }
        }
        if (ok) {
            propertiesByNameBuilder.putAll(propstatPropertiesByName);
        }
    }

    private static Map<QName, DavProperty> readProp(XMLStreamReader reader) throws XMLStreamException {
        var propertiesByNameBuilder = ImmutableMap.<QName, DavProperty>builder();
        while (reader.nextTag() == START_ELEMENT) {
            propertiesByNameBuilder.put(reader.getName(), readProperty(reader));
        }
        return propertiesByNameBuilder.buildKeepingLast();
    }

    /// Reads the property the reader is at the start of, up to and including its end.
    private static DavProperty readProperty(XMLStreamReader reader) throws XMLStreamException {
        // a display name runs to a few dozen characters and an event's iCalendar data to a few kilobytes, with nothing ahead of reading it to tell which
        var text = new StringBuilder();
        var children = ImmutableSet.<QName>builder();
        String href = null;
        int depth = 1;
        while (depth > 0) {
            switch (reader.next()) {
                case START_ELEMENT -> {
                    if (depth == 1) {
                        children.add(reader.getName());
                    }
                    if (href == null && reader.getName().equals(HREF)) {
                        href = reader.getElementText().strip();
                    } else {
                        depth++;
                    }
                }
                case END_ELEMENT -> depth--;
                case CHARACTERS -> {
                    if (depth == 1) {
                        text.append(reader.getText());
                    }
                }
                default -> {
                }
            }
        }
        return new DavProperty(text.toString().strip(), children.build(), Optional.ofNullable(href));
    }

    /// @param statusLine such as `HTTP/1.1 200 OK`
    private static boolean isOk(String statusLine) {
        String[] parts = statusLine.strip().split(" ", 3);
        return parts.length >= 2 && parts[1].equals(OK_STATUS_CODE);
    }

    private static void skipElement(XMLStreamReader reader) throws XMLStreamException {
        int depth = 1;
        while (depth > 0) {
            switch (reader.next()) {
                case START_ELEMENT -> depth++;
                case END_ELEMENT -> depth--;
                default -> {
                }
            }
        }
    }

    private static XMLInputFactory createXmlInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        return factory;
    }

    /// A calendar in the account's calendar home.
    ///
    /// @param href        the href the server names it by, which serves as its id
    /// @param url         where its events are read from
    /// @param displayName the calendar's title as the server lists it
    public record CalendarCollection(String href, HttpUrl url, String displayName) implements StringFormattable {
        public CalendarCollection {
            checkNotNull(href);
            checkNotNull(url);
            checkNotNull(displayName);
        }

        @Override
        public String toString() {
            return toString(96);
        }

        /// Redacts all three, since the href and the URL open with the account id and the title is the user's own.
        @Override
        public void formatTo(Appendable appendable) {
            Append.to(appendable, "CalendarCollection{href='");
            appendRedacted(appendable, href);
            Append.to(appendable, "', url='");
            appendRedacted(appendable, url.toString());
            Append.to(appendable, "', displayName='");
            appendRedacted(appendable, displayName);
            Append.to(appendable, "'}");
        }
    }

    @VisibleForTesting
    record DavResponse(String href, Map<QName, DavProperty> propertiesByName) implements StringFormattable {
        DavResponse {
            checkNotNull(href);
            checkNotNull(propertiesByName);
        }

        @Override
        public String toString() {
            return toString(128);
        }

        @Override
        public void formatTo(Appendable appendable) {
            Append.to(appendable, "DavResponse{href='");
            appendRedacted(appendable, href);
            Append.to(appendable, "', propertiesByName=");
            Append.to(appendable, propertiesByName);
            Append.to(appendable, '}');
        }
    }

    /// @param text     the property's own text, such as a display name or a calendar's iCalendar data
    /// @param children the names of its child elements, such as the types a `resourcetype` lists
    /// @param href     the first `href` inside it, such as the one a `calendar-home-set` holds, or empty where it holds none
    @VisibleForTesting
    record DavProperty(String text, Set<QName> children, Optional<String> href) implements StringFormattable {
        DavProperty {
            checkNotNull(text);
            checkNotNull(children);
            checkNotNull(href);
        }

        @Override
        public String toString() {
            return toString(96);
        }

        @Override
        public void formatTo(Appendable appendable) {
            Append.to(appendable, "DavProperty{text='");
            appendRedacted(appendable, text);
            Append.to(appendable, "', children=");
            Append.to(appendable, children);
            Append.to(appendable, ", href='");
            appendRedacted(appendable, href);
            Append.to(appendable, "'}");
        }
    }
}
