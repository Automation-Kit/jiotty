package net.yudichev.jiotty.connector.expopush;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/// Sends push notifications through the Expo Push Service.
///
/// The sender batches messages into groups of up to 100 (the Expo API limit) and fans them out with a single HTTP POST per batch. Synchronously reported ticket
/// errors of type `DeviceNotRegistered` notify the configured [DeadTokenListener] so the server can prune invalid device tokens.
public interface ExpoPushSender {
    /// Sends the given messages in parallel. The returned future completes when all requests have been accepted (or have failed) — it does not wait for actual
    /// APNs/FCM delivery.
    CompletableFuture<Void> send(List<ExpoPushMessage> messages);
}
