package net.yudichev.jiotty.user.persistence;

import java.time.Duration;

final class UserPersistenceTestTimeouts {
    /// Bounds a wait for a delivery to a subscriber, and for a [UserPersistence] call in the subscription tests; only a deadlock reaches it.
    public static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(5);

    private UserPersistenceTestTimeouts() {
    }
}
