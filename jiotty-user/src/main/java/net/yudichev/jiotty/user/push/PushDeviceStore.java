package net.yudichev.jiotty.user.push;

import net.yudichev.jiotty.persistence.varstore.VarStore;

import java.util.List;
import java.util.concurrent.CompletableFuture;

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
}
