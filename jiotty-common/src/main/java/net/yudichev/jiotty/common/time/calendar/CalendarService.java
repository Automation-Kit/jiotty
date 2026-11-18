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
    ///         the provider's authentication is still in progress
    CompletableFuture<CalendarsResult> retrieveCalendars();

    Closeable subscribeToAuthState(Consumer<AuthState> consumer);

    /// Submits the configured credentials to the provider once more, after the provider has rejected them. The outcome is delivered to the
    /// [#subscribeToAuthState] subscribers as one [AuthState].
    ///
    /// @implSpec The default does nothing, which suits a service that keeps calling the provider after a rejection. A service that stops calling the provider
    ///           after a rejection must override this method, and must then resume calling the provider on an [AuthState.Success].
    default void retryAuthentication() {
    }

    /// Deletes every credential this service stored outside its configuration and withdraws the access the provider granted it. The service authenticates
    /// nothing afterwards.
    ///
    /// @return completes once the provider has withdrawn the access
    /// @implSpec The default suits a service whose credentials live only in its configuration.
    default CompletableFuture<Void> disconnect() {
        return CompletableFuture.completedFuture(null);
    }

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
