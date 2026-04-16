package net.yudichev.jiotty.connector.expopush;

/// Receives asynchronous events produced by [ExpoPushSender] that cannot be surfaced through the returned [java.util.concurrent.CompletableFuture] —
/// token-state changes discovered during ticket processing or receipt polling, and unexpected technical failures on the fire-and-forget receipt polling path.
/// Implementations must not throw; exceptions are not caught by the sender.
public interface ExpoPushEventListener {
    ExpoPushEventListener NOOP = new ExpoPushEventListener() {
        @Override
        public void onDeadToken(String expoPushToken) {
        }

        @Override
        public void onUnexpectedError(String description) {
        }
    };

    /// Expo reported that this push token is no longer registered with APNs/FCM and must be removed from the server's device store.
    void onDeadToken(String expoPushToken);

    /// An unexpected condition that the caller should surface (e.g. admin alert): a ticket or receipt error other than `DeviceNotRegistered`, an unexpected
    /// response shape, or an HTTP failure during receipt polling. The description is already redacted — safe to log or include in alert bodies.
    void onUnexpectedError(String description);
}
