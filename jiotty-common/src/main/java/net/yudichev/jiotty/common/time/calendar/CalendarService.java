package net.yudichev.jiotty.common.time.calendar;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.security.AuthState;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface CalendarService {
    CompletableFuture<List<Calendar>> retrieveCalendars();

    Closeable subscribeToAuthState(Consumer<AuthState> consumer);
}
