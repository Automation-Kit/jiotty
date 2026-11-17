package net.yudichev.jiotty.user.push;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.persistence.varstore.VarStore;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;

/// Per-user registry of push-capable devices. All operations dispatch to an internal single-threaded executor, so callers must chain on the returned future
/// rather than assume synchronous completion.
public interface PushDeviceStore {
    /// The devices recorded in `varStore`, read directly with no store started. The read is a snapshot: a registration landing at the same instant may or may
    /// not be in it.
    static List<PushDeviceRecord> listStored(VarStore varStore) {
        return PushDeviceStoreImpl.listStored(checkNotNull(varStore));
    }

    /// Inserts or replaces the record keyed by [PushDeviceRecord#deviceId()]. A rotated token reaches the store through a re-registration, not a separate
    /// mutator.
    CompletableFuture<Void> upsert(PushDeviceRecord record);

    /// Removes the device with this `deviceId`. No-op if absent.
    CompletableFuture<Void> remove(String deviceId);

    /// Removes every device whose current token equals `token`. Called by the send transport when Expo reports `DeviceNotRegistered`, to reconcile server state
    /// with APNs/FCM state.
    CompletableFuture<Void> pruneByToken(String token);

    CompletableFuture<List<PushDeviceRecord>> list();

    /// Subscribes `listener` and delivers the registered devices to it. After each [#upsert], and after each [#remove] or [#pruneByToken] that removed a
    /// device, delivers the whole list of registered devices again. Every delivery runs on the store's executor, one at a time. The first delivery comes
    /// before any other, and the later ones follow the order of the writes that caused them. **A listener must return without blocking**, because the store's
    /// other operations wait for it. A listener that throws stays subscribed and receives the next delivery.
    ///
    /// If reading the registered devices for the first delivery fails, the failure reaches the failure handler of the store's executor, and `listener` is not
    /// subscribed.
    ///
    /// @return a handle that unsubscribes `listener`
    Closeable subscribe(Consumer<? super List<PushDeviceRecord>> listener);
}
