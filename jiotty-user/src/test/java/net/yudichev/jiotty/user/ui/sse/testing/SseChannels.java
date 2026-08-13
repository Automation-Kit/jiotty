package net.yudichev.jiotty.user.ui.sse.testing;

import net.yudichev.jiotty.adminalerts.AdminAlertService;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.user.ui.sse.SseChannel;

/// Builds channels the way each feature's wiring does, for a test that constructs its component directly rather than through Guice.
public final class SseChannels {
    private SseChannels() {
    }

    public static SseChannel.Factory factory(CurrentDateTimeProvider timeProvider, AdminAlertService alertService) {
        return (name, executor, jsonWriter, maxClients) -> new SseChannel(name, executor, timeProvider, jsonWriter, alertService, maxClients);
    }
}
