package net.yudichev.jiotty.common.time.calendar;

import static com.google.common.base.Preconditions.checkNotNull;

/// What a future returned by a [CalendarService] or a [Calendar] completes exceptionally with when the provider rejects the configured credentials on that
/// call. The same call must not be retried with backoff; [CalendarService#retryAuthentication()] submits the configured credentials to the provider again.
public final class CalendarAuthorisationException extends RuntimeException {
    public CalendarAuthorisationException(String message) {
        super(checkNotNull(message));
    }

    public CalendarAuthorisationException(String message, Throwable cause) {
        super(checkNotNull(message), checkNotNull(cause));
    }
}
