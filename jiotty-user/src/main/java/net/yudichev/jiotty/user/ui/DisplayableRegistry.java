package net.yudichev.jiotty.user.ui;

import net.yudichev.jiotty.common.lang.Closeable;

import java.util.Collection;
import java.util.Optional;
import java.util.function.Consumer;

/// Per-[UIServer] registry of [Displayable]s. Holds the current set of registered displayables, throttles per-displayable update events, and exposes hooks
/// subscribers can use to react to (a) updates from existing displayables and (b) newly-registered displayables.
public interface DisplayableRegistry {
    /// Registers a displayable. Throws if a displayable with the same id is already registered.
    Closeable register(Displayable displayable);

    /// Looks up a displayable by id.
    Optional<Displayable> find(String id);

    /// Returns a snapshot of all currently-registered displayables in registration order.
    Collection<Displayable> all();

    /// Subscribes to per-displayable update events (data changed on a previously-registered displayable). Fires throttled by the registry's internal cadence.
    ///
    /// Late-joiner contract: **no image is delivered on subscribe by design** — the event is a delta describing one displayable's data change. The current data
    /// state of each displayable is delivered via [#subscribeToRegistrations] (which fires the consumer once per already-registered displayable when the new
    /// subscriber joins). A consumer that wants both deltas and an initial image must subscribe to both.
    Closeable subscribeToUpdates(Consumer<Displayable> onUpdated);

    /// Subscribes to registration events. `onRegistered` fires when a new displayable joins.
    ///
    /// Late-joiner contract: on subscribe the consumer is fired once for each already-registered displayable (so a subscriber that arrives after some
    /// registrations have happened receives the full set retroactively), then fires going forward for every subsequent registration.
    Closeable subscribeToRegistrations(Consumer<Displayable> onRegistered);
}
