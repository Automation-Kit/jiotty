package net.yudichev.jiotty.user.ui;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.user.ui.options.Option;

import java.util.Collection;
import java.util.Optional;

/// Per-[UIServer] registry of user-facing [Option]s. Holds the current set of registered options and notifies subscribers when the snapshot changes.
public interface OptionRegistry {
    /// Registers an option. Throws if an option with the same key is already registered.
    Closeable register(Option<?> option);

    /// Looks up an option by its `meta().key()`.
    Optional<Option<?>> find(String key);

    /// Returns a snapshot of all currently-registered options in registration order.
    Collection<Option<?>> all();

    /// Subscribes to "the option snapshot has changed" notifications. Fires on register, unregister, and any option value change. Subscribers must not block
    /// the calling thread — the runnable runs on the registry's notification thread.
    ///
    /// Late-joiner contract: the runnable fires once on subscribe (treat it as the initial snapshot-changed event) so a fresh subscriber sees the current state
    /// and reads it back via [#all]. The event is payload-less by design — consumers must always pull state from [#all] anyway, and a single coalescing trigger
    /// is sufficient.
    Closeable subscribeToSnapshotChanges(Runnable onChanged);
}
