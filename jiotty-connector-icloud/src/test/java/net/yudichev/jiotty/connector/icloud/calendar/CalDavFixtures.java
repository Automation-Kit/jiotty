package net.yudichev.jiotty.connector.icloud.calendar;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

import static net.yudichev.jiotty.common.rest.HttpStatuses.MULTI_STATUS_207;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.response;
import static net.yudichev.jiotty.connector.icloud.calendar.CalDavClient.XML;

/// An iCloud account's CalDAV responses, shaped as iCloud sends them, for the tests of the connector's exchanges.
final class CalDavFixtures {
    static final String ACCOUNT_ID = "1051121623";
    static final String CALENDAR_HREF = "/" + ACCOUNT_ID + "/calendars/27A6139E-FFFD-4C0F-9DCC-B914B9CB04E7/";
    static final String CALENDAR_TITLE = "Family Holidays";
    /// The listing of a home holding the one calendar [#CALENDAR_HREF] titled [#CALENDAR_TITLE], with CalDAV's namespace declared as the default one.
    static final String CALENDARS_RESPONSE = """
                                             <multistatus xmlns="DAV:">
                                               <response>
                                                 <href>%s</href>
                                                 <propstat>
                                                   <prop>
                                                     <resourcetype><collection/><calendar xmlns="urn:ietf:params:xml:ns:caldav"/></resourcetype>
                                                     <displayname>%s</displayname>
                                                   </prop>
                                                   <status>HTTP/1.1 200 OK</status>
                                                 </propstat>
                                               </response>
                                             </multistatus>""".formatted(CALENDAR_HREF, CALENDAR_TITLE);
    /// The calendar home as iCloud names it, port included.
    private static final String HOME_URL = "https://p42-caldav.icloud.com:443/" + ACCOUNT_ID + "/calendars/";
    static final HttpUrl HOME = HttpUrl.get(HOME_URL);
    /// The discovery response naming [#HOME_URL], with CalDAV's namespace declared as the default one.
    static final String HOME_RESPONSE = """
                                        <multistatus xmlns="DAV:">
                                          <response>
                                            <href>/</href>
                                            <propstat>
                                              <prop>
                                                <calendar-home-set xmlns="urn:ietf:params:xml:ns:caldav">
                                                  <href xmlns="DAV:">%s</href>
                                                </calendar-home-set>
                                              </prop>
                                              <status>HTTP/1.1 200 OK</status>
                                            </propstat>
                                          </response>
                                        </multistatus>""".formatted(HOME_URL);

    private CalDavFixtures() {
    }

    static Response createMultiStatusResponse(Request request, String xml) {
        return response(request, MULTI_STATUS_207, XML, xml);
    }

    static Response createErrorResponse(Request request, int statusCode) {
        return response(request, statusCode, XML, "<error/>");
    }
}
