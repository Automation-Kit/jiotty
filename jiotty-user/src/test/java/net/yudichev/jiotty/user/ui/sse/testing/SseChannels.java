package net.yudichev.jiotty.user.ui.sse.testing;

import net.yudichev.jiotty.adminalerts.AdminAlertService;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.user.ui.sse.SseAdmission;
import net.yudichev.jiotty.user.ui.sse.SseChannel;

/// Static factories of [SseChannel.Factory] wired as each feature's Guice module wires it, for constructing a component without Guice.
public final class SseChannels {
    private SseChannels() {
    }

    /// Returns a factory whose channels admit every stream through [SseAdmission#unlimited()].
    public static SseChannel.Factory createFactory(CurrentDateTimeProvider timeProvider, AdminAlertService alertService) {
        return createFactory(timeProvider, alertService, SseAdmission.unlimited());
    }

    /// Returns a factory whose channels count their streams against `admission`.
    public static SseChannel.Factory createFactory(CurrentDateTimeProvider timeProvider, AdminAlertService alertService, SseAdmission admission) {
        return (name, executor, jsonWriter, maxClients) -> new SseChannel(name, executor, timeProvider, jsonWriter, alertService, admission, maxClients);
    }
}
