package net.yudichev.jiotty.connector.google.calendar;

import com.google.api.client.googleapis.services.AbstractGoogleClientRequest;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;

import java.io.IOException;

import static com.google.common.base.Preconditions.checkNotNull;

final class GoogleCalendarRequests {
    private GoogleCalendarRequests() {
    }

    /// Executes a Google Calendar API request, rewrapping the checked [IOException] as an unchecked failure tagged with the given description. Retry and
    /// back-off are the caller's responsibility; callers that need them wrap these calls in a [RetryableOperationExecutor].
    static <T> T execute(String description, AbstractGoogleClientRequest<T> request) {
        try {
            return checkNotNull(request).execute();
        } catch (IOException e) {
            throw new RuntimeException("Failed to " + description, e);
        }
    }
}
