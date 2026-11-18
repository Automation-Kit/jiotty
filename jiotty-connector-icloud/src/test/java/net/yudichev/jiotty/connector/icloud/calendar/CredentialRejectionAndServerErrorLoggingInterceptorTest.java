package net.yudichev.jiotty.connector.icloud.calendar;

import com.google.common.net.HttpHeaders;
import net.yudichev.jiotty.common.testutil.LogCapture;
import net.yudichev.jiotty.connector.icloud.calendar.CalDavClient.Exchange;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;

import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static net.yudichev.jiotty.common.rest.HttpStatuses.MULTI_STATUS_207;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NOT_FOUND_404;
import static net.yudichev.jiotty.common.rest.HttpStatuses.SERVICE_UNAVAILABLE_503;
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.createResponseWithUnreadableBody;
import static net.yudichev.jiotty.connector.icloud.calendar.CalDavClient.WELL_KNOWN_CALDAV_URL;
import static net.yudichev.jiotty.connector.icloud.calendar.CalDavFixtures.ERROR_BODY;
import static net.yudichev.jiotty.connector.icloud.calendar.CalDavFixtures.createErrorResponse;
import static net.yudichev.jiotty.connector.icloud.calendar.CredentialRejectionAndServerErrorLoggingInterceptor.RESPONDING_INSTANCE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CredentialRejectionAndServerErrorLoggingInterceptorTest {
    private static final String LOG_SUBJECT_ID = "test-user";
    private static final String PARTITION_HEADER = "X-Apple-User-Partition";
    private static final String PARTITION = "partition-42";
    private static final String REQUEST_UUID_HEADER = "X-Apple-Request-UUID";
    private static final String REQUEST_UUID = "a680aa92-9a28-4998-8358-029a57f25f2f";
    private static final Request REQUEST = new Request.Builder().url(WELL_KNOWN_CALDAV_URL).tag(Exchange.class, Exchange.CALENDAR_HOME).build();

    private final CredentialRejectionAndServerErrorLoggingInterceptor interceptor = new CredentialRejectionAndServerErrorLoggingInterceptor(LOG_SUBJECT_ID);
    @Mock
    private Interceptor.Chain chain;

    @Test
    void aRejection_logsWhatDistinguishesItAndNeitherTheBodyNorWhatIdentifiesTheAccount() {
        String server = "AppleHttpServer/89717d3e289b";
        String challenge = "Basic realm=\"MMCalDav\"";
        Response response = createErrorResponse(REQUEST, UNAUTHORIZED_401)
                .newBuilder()
                .sentRequestAtMillis(1_000)
                .receivedResponseAtMillis(1_250)
                .header(HttpHeaders.SERVER, server)
                .header(HttpHeaders.WWW_AUTHENTICATE, challenge)
                .header(RESPONDING_INSTANCE_HEADER, "caldavj:3:p42-caldavj-7c985d4658-l46wb:8080")
                .header(PARTITION_HEADER, PARTITION)
                .header(REQUEST_UUID_HEADER, REQUEST_UUID)
                .build();

        assertThat(interceptAndCaptureLog(response)).singleElement().satisfies(line -> assertThat(line)
                .startsWith("[" + LOG_SUBJECT_ID + "] The server answered the CALENDAR_HOME request with 401 in 250 ms: body of "
                            + ERROR_BODY.length() + " bytes")
                .contains(PARTITION_HEADER, REQUEST_UUID_HEADER)
                .contains(HttpHeaders.SERVER + "=" + server, HttpHeaders.WWW_AUTHENTICATE + "=" + challenge, RESPONDING_INSTANCE_HEADER + "=caldavj]")
                .doesNotContain(PARTITION, REQUEST_UUID, ERROR_BODY, "p42-caldavj"));
    }

    @Test
    void aServerError_isLogged() {
        assertThat(interceptAndCaptureLog(createErrorResponse(REQUEST, SERVICE_UNAVAILABLE_503)))
                .singleElement()
                .satisfies(line -> assertThat(line).contains("request with 503"));
    }

    @ParameterizedTest
    @ValueSource(ints = {MULTI_STATUS_207, NOT_FOUND_404})
    void anyOtherStatus_isNotLogged(int status) {
        assertThat(interceptAndCaptureLog(createErrorResponse(REQUEST, status))).isEmpty();
    }

    /// The part of the header before its first colon names the kind of server, and the format of a value with no colon is unknown.
    @Test
    void aRespondingInstanceWithoutAColon_isNotLogged() {
        Response response = createErrorResponse(REQUEST, UNAUTHORIZED_401).newBuilder().header(RESPONDING_INSTANCE_HEADER, "opaque-value").build();

        assertThat(interceptAndCaptureLog(response)).singleElement().satisfies(line -> assertThat(line).doesNotContain("opaque-value"));
    }

    @Test
    void theBodyStaysReadableAfterItIsMeasured() {
        Response interceptedResponse = intercept(createErrorResponse(REQUEST, UNAUTHORIZED_401));

        assertThat(getAsUnchecked(() -> interceptedResponse.body().string())).isEqualTo(ERROR_BODY);
    }

    /// The response reaches the caller, which classifies the failure by the response's status.
    @Test
    void aBodyThatCannotBeRead_isLoggedWithTheFailureAndTheResponsePassesThrough() {
        Response response = createResponseWithUnreadableBody(REQUEST, UNAUTHORIZED_401, new IOException("connection reset"));

        assertThat(interceptAndCaptureLog(response)).singleElement().satisfies(line -> assertThat(line)
                .startsWith("[" + LOG_SUBJECT_ID + "] The server answered the CALENDAR_HOME request with 401")
                .contains("body of unknown length", "connection reset"));
    }

    private List<String> interceptAndCaptureLog(Response response) {
        try (var log = LogCapture.capture(CredentialRejectionAndServerErrorLoggingInterceptor.class, Level.INFO)) {
            assertThat(intercept(response)).isSameAs(response);
            return log.lines().stream().map(LogCapture.CapturedLine::message).toList();
        }
    }

    private Response intercept(Response response) {
        return getAsUnchecked(() -> {
            when(chain.request()).thenReturn(REQUEST);
            when(chain.proceed(REQUEST)).thenReturn(response);
            return interceptor.intercept(chain);
        });
    }
}
