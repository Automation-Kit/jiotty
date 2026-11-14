package net.yudichev.jiotty.connector.miele;

import net.yudichev.jiotty.common.lang.Closeable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface MieleDishwasher {
    /// Subscribes `eventHandler` to the dishwasher's event stream. The handler first receives the latest of the stream's [MieleStreamConnected] or
    /// [MieleStreamDisconnected], [MieleDevice] and [MieleActions] events, where the stream has delivered one, then each event as the stream delivers it.
    /// It is invoked with one event at a time.
    ///
    /// @param eventHandler invoked with each event; an exception it throws is logged
    /// @return the subscription. Closing it ends the deliveries to `eventHandler`, apart from a delivery already under way.
    Closeable subscribeToEvents(Consumer<? super MieleEvent> eventHandler);

    CompletableFuture<List<MieleProgram>> getPrograms();

    CompletableFuture<MieleActions> getActions();

    CompletableFuture<Void> startProgram(int programId);

    CompletableFuture<Void> powerOn();

    CompletableFuture<Void> powerOff();
}
