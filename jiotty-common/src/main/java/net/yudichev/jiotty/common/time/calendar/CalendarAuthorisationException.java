package net.yudichev.jiotty.common.time.calendar;

import static com.google.common.base.Preconditions.checkNotNull;

/// What a future returned by a [CalendarService] or a [Calendar] completes exceptionally with when the provider rejects the configured credentials. Signals
/// that the failure is permanent for those credentials and must not be retried with backoff — the user has to supply new credentials.
public final class CalendarAuthorisationException extends RuntimeException {
    public CalendarAuthorisationException(String message) {
        super(checkNotNull(message));
    }

    public CalendarAuthorisationException(String message, Throwable cause) {
        super(checkNotNull(message), checkNotNull(cause));
    }
}
