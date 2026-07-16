package net.yudichev.jiotty.common.time.calendar;

import com.google.common.collect.ImmutableList;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.security.AuthState;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface CalendarService {
    /// Retrieves the calendars available to the configured account.
    ///
    /// @return a future completing with [CalendarsResult.Calendars] holding the account's calendar list, or with [CalendarsResult.NotYetAuthenticated] while
    ///         the provider's authentication is still in progress. Completes exceptionally with [CalendarAuthorisationException] when the provider rejects the
    ///         configured credentials — a permanent failure the caller must surface for re-authentication rather than retry.
    CompletableFuture<CalendarsResult> retrieveCalendars();

    Closeable subscribeToAuthState(Consumer<AuthState> consumer);

    /// Outcome of [#retrieveCalendars].
    sealed interface CalendarsResult {
        /// The account's calendar list. An empty list means the account genuinely has no calendars.
        ///
        /// @param calendars the account's calendars
        record Calendars(List<Calendar> calendars) implements CalendarsResult {
            public Calendars {
                calendars = ImmutableList.copyOf(calendars);
            }
        }

        /// The provider's authentication is still in progress, so the calendar list is not yet known.
        final class NotYetAuthenticated implements CalendarsResult {
            public static final NotYetAuthenticated INSTANCE = new NotYetAuthenticated();

            private NotYetAuthenticated() {
            }
        }
    }
}
