package net.yudichev.jiotty.user.ui.sse;

/// A budget of concurrent streams, shared by everything that draws on it. Safe to call from any thread.
public interface SseAdmission {
    /// A budget that admits every stream and records nothing.
    static SseAdmission unlimited() {
        return UnlimitedSseAdmission.INSTANCE;
    }

    /// Takes a slot for one more stream, or refuses when the budget is spent.
    ///
    /// @return whether the stream may open; one that may must later give its slot back through [#release()]
    boolean tryAdmit();

    /// Gives back the slot one stream held.
    void release();

    /// Records a stream a channel refused because the channel itself was full.
    void recordChannelFull();

    /// Records a stream dropped because its client fell too far behind in reading.
    void recordSlowReaderDropped();
}
