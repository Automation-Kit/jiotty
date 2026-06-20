package net.yudichev.jiotty.common.time.calendar;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.security.AuthState;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface CalendarService {
    /// Retrieves the calendars available to the configured account. The returned future completes exceptionally with [CalendarAuthorisationException] when the
    /// provider rejects the configured credentials — a permanent failure the caller must surface for re-authentication rather than retry.
    CompletableFuture<List<Calendar>> retrieveCalendars();

    Closeable subscribeToAuthState(Consumer<AuthState> consumer);
}
