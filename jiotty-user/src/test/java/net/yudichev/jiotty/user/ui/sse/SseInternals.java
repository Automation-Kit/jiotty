package net.yudichev.jiotty.user.ui.sse;

import java.time.Duration;

/// Package-private SSE constants, re-exported for tests outside this package.
public final class SseInternals {
    public static final String CHANNEL_FULL_ERROR = SseChannel.CHANNEL_FULL_ERROR;
    public static final Duration HEARTBEAT_PERIOD = SseChannel.HEARTBEAT_PERIOD;
    public static final String DROPPED_COUNTER = SseAdmissionImpl.DROPPED_COUNTER;
    public static final String ACTIVE_CLIENTS_GAUGE = SseAdmissionImpl.ACTIVE_CLIENTS_GAUGE;
    public static final int PENDING_FRAMES_CAPACITY = PendingFrames.CAPACITY;

    private SseInternals() {
    }
}
