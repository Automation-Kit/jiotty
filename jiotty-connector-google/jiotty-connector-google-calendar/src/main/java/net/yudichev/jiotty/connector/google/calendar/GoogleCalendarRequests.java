package net.yudichev.jiotty.connector.google.calendar;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.googleapis.services.AbstractGoogleClientRequest;
import com.google.common.annotations.VisibleForTesting;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;
import net.yudichev.jiotty.common.time.calendar.CalendarAuthorisationException;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.Optional;
import java.util.function.Consumer;

import static net.yudichev.jiotty.common.rest.HttpStatuses.FORBIDDEN_403;
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;

final class GoogleCalendarRequests {
    /// The reason of the forbidden response to a request whose grant lacks a scope the request needs, as when the user unticks one of the scopes on the
    /// consent screen.
    @VisibleForTesting
    static final String INSUFFICIENT_PERMISSIONS_REASON = "insufficientPermissions";

    private GoogleCalendarRequests() {
    }

    /// Executes a Google Calendar API request once; wrap the call in a [RetryableOperationExecutor] where retries are needed.
    ///
    /// @param description                what the request does, completing "Failed to …" and "trying to …" in the failure messages
    /// @param credentialRejectionHandler invoked with the rejection message when the request is rejected as unauthorised, or as forbidden with reason
    ///                                   [#INSUFFICIENT_PERMISSIONS_REASON]
    /// @throws CalendarAuthorisationException when the request is rejected that way, carrying the rejection message `credentialRejectionHandler` received
    /// @throws RuntimeException               on any other error response, as [#createResponseFailure] builds it, or on a transport failure, as
    ///                                        [#createTransportFailure] builds it
    public static <T> T execute(String description, AbstractGoogleClientRequest<T> request, Consumer<String> credentialRejectionHandler) {
        try {
            return request.execute();
        } catch (GoogleJsonResponseException e) {
            Optional<String> reason = errorReason(e);
            int statusCode = e.getStatusCode();
            if (statusCode == UNAUTHORIZED_401 || (statusCode == FORBIDDEN_403 && reason.filter(INSUFFICIENT_PERMISSIONS_REASON::equals).isPresent())) {
                throw reportCredentialRejection(e, reason, description, credentialRejectionHandler);
            }
            throw createResponseFailure(e, reason, description);
        } catch (IOException e) {
            throw createTransportFailure(e, description);
        }
    }

    /// Returns the `reason` of the first error the Calendar API error response `e` lists, or empty when it lists none.
    public static Optional<String> errorReason(GoogleJsonResponseException e) {
        return Optional.ofNullable(e.getDetails())
                       .map(GoogleJsonError::getErrors)
                       .filter(errors -> !errors.isEmpty())
                       .map(errors -> errors.getFirst().getReason());
    }

    /// Invokes `credentialRejectionHandler` with the rejection message for a rejected credential and returns the exception to throw for it.
    ///
    /// @param e                          the rejecting response, left out as the exception's cause because the message of `e` names the request URL
    /// @param reason                     the error reason `e` lists, as [#errorReason] gives it
    /// @param description                what the rejected request does, completing "trying to …"
    /// @param credentialRejectionHandler invoked with the rejection message
    /// @return the [CalendarAuthorisationException] carrying the rejection message
    public static CalendarAuthorisationException reportCredentialRejection(GoogleJsonResponseException e,
                                                                           Optional<String> reason,
                                                                           String description,
                                                                           Consumer<String> credentialRejectionHandler) {
        String rejection = "Google Calendar API rejected the credential (" + describeResponse(e, reason.orElse(null)) + ") trying to " + description;
        credentialRejectionHandler.accept(rejection);
        return new CalendarAuthorisationException(rejection);
    }

    /// Returns the exception to throw for an error response that does not reject the credential.
    ///
    /// @param e           the error response, left out as the exception's cause because the message of `e` names the request URL
    /// @param reason      the error reason `e` lists, as [#errorReason] gives it
    /// @param description what the failed request does, completing "Failed to …"
    public static RuntimeException createResponseFailure(GoogleJsonResponseException e, Optional<String> reason, String description) {
        return new RuntimeException("Failed to " + description + ": " + describeResponse(e, reason.orElse(null)));
    }

    /// Returns the exception to throw for a request that failed before any response arrived.
    ///
    /// @param e           the transport failure, kept as the exception's cause because it names no URL
    /// @param description what the failed request does, completing "Failed to …"
    public static RuntimeException createTransportFailure(IOException e, String description) {
        return new RuntimeException("Failed to " + description, e);
    }

    /// Describes the error response `e` by its status and its error reason alone, because an events URL holds the calendar id, which is the user's email
    /// address, and Google's message is free text that no test of ours can keep free of it.
    ///
    /// @param reason the error reason `e` lists, or `null` when it lists none
    private static String describeResponse(GoogleJsonResponseException e, @Nullable String reason) {
        String status = "HTTP " + e.getStatusCode();
        return reason == null ? status : status + ' ' + reason;
    }
}
