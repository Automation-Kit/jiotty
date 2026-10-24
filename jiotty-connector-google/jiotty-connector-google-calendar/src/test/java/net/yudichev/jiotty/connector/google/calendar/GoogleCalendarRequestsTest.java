package net.yudichev.jiotty.connector.google.calendar;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.googleapis.services.AbstractGoogleClientRequest;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import net.yudichev.jiotty.common.time.calendar.CalendarAuthorisationException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_GATEWAY_502;
import static net.yudichev.jiotty.common.rest.HttpStatuses.FORBIDDEN_403;
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarRequests.INSUFFICIENT_PERMISSIONS_REASON;
import static net.yudichev.jiotty.connector.google.calendar.GoogleErrorReasons.AUTH_ERROR_REASON;
import static net.yudichev.jiotty.connector.google.calendar.GoogleErrorReasons.FORBIDDEN_FOR_NON_ORGANIZER_REASON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoogleCalendarRequestsTest {
    private final List<String> credentialRejections = new ArrayList<>();

    @Test
    void execute_returnsResponse(@Mock AbstractGoogleClientRequest<String> request) throws IOException {
        when(request.execute()).thenReturn("response");

        assertThat(GoogleCalendarRequests.execute("fetch the thing", request, credentialRejections::add)).isEqualTo("response");
        assertThat(credentialRejections).isEmpty();
    }

    @Test
    void execute_rewrapsIoExceptionWithDescription(@Mock AbstractGoogleClientRequest<String> request) throws IOException {
        var cause = new IOException("network down");
        when(request.execute()).thenThrow(cause);

        assertThatThrownBy(() -> GoogleCalendarRequests.execute("fetch the thing", request, credentialRejections::add))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Failed to fetch the thing")
                .hasCause(cause);
        assertThat(credentialRejections).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void execute_credentialRejected_reportsTheRejectedCredentialAndFailsAsAnAuthorisationFailure(String rejection,
                                                                                                 GoogleJsonResponseException errorResponse,
                                                                                                 @Mock AbstractGoogleClientRequest<String> request)
            throws IOException {
        when(request.execute()).thenThrow(errorResponse);

        assertThatThrownBy(() -> GoogleCalendarRequests.execute("fetch the thing", request, credentialRejections::add))
                .isInstanceOf(CalendarAuthorisationException.class)
                .hasMessage(rejection)
                .hasNoCause();
        assertThat(credentialRejections).containsExactly(rejection);
    }

    static Stream<Arguments> execute_credentialRejected_reportsTheRejectedCredentialAndFailsAsAnAuthorisationFailure() {
        String rejectionPrefix = "Google Calendar API rejected the credential (HTTP ";
        return Stream.of(arguments(rejectionPrefix + "403 " + INSUFFICIENT_PERMISSIONS_REASON + ") trying to fetch the thing",
                                   createResponseException(FORBIDDEN_403, "No scope.", INSUFFICIENT_PERMISSIONS_REASON)),
                         arguments(rejectionPrefix + "401 " + AUTH_ERROR_REASON + ") trying to fetch the thing",
                                   createResponseException(UNAUTHORIZED_401, null, AUTH_ERROR_REASON)),
                         arguments(rejectionPrefix + "401) trying to fetch the thing", createResponseException(UNAUTHORIZED_401, null, List.of())));
    }

    /// The error response is left out as a cause because its message names the request URL, which for an events request holds the user's email address.
    /// The description leaves out Google's free-text message too, which the exact `hasMessage` pins.
    @ParameterizedTest(name = "{0}")
    @MethodSource
    void execute_otherErrorResponse_failsWithItsDescriptionAndNoCause(String caseName,
                                                                      String failure,
                                                                      GoogleJsonResponseException errorResponse,
                                                                      @Mock AbstractGoogleClientRequest<String> request) throws IOException {
        when(request.execute()).thenThrow(errorResponse);

        assertThatThrownBy(() -> GoogleCalendarRequests.execute("fetch the thing", request, credentialRejections::add))
                .isNotInstanceOf(CalendarAuthorisationException.class)
                .hasMessage(failure)
                .hasNoCause();
        assertThat(credentialRejections).isEmpty();
    }

    static Stream<Arguments> execute_otherErrorResponse_failsWithItsDescriptionAndNoCause() {
        return Stream.of(arguments("another reason",
                                   "Failed to fetch the thing: HTTP 403 " + FORBIDDEN_FOR_NON_ORGANIZER_REASON,
                                   createResponseException(FORBIDDEN_403, "Not the organiser.", FORBIDDEN_FOR_NON_ORGANIZER_REASON)),
                         arguments("no error listed", "Failed to fetch the thing: HTTP 403", createResponseException(FORBIDDEN_403, null, List.of())),
                         arguments("no JSON body",
                                   "Failed to fetch the thing: HTTP " + BAD_GATEWAY_502,
                                   new GoogleJsonResponseException(new HttpResponseException.Builder(BAD_GATEWAY_502, "Bad Gateway", new HttpHeaders()),
                                                                   null)));
    }

    /// @param message the message Google gives, or `null` for a response without one
    private static GoogleJsonResponseException createResponseException(int statusCode, @Nullable String message, String reason) {
        var errorInfo = new GoogleJsonError.ErrorInfo();
        errorInfo.setReason(reason);
        return createResponseException(statusCode, message, List.of(errorInfo));
    }

    /// @param message the message Google gives, or `null` for a response without one
    private static GoogleJsonResponseException createResponseException(int statusCode, @Nullable String message, List<GoogleJsonError.ErrorInfo> errors) {
        var details = new GoogleJsonError();
        details.setCode(statusCode);
        details.setMessage(message);
        details.setErrors(errors);
        return new GoogleJsonResponseException(new HttpResponseException.Builder(statusCode, "Rejected", new HttpHeaders()), details);
    }
}
