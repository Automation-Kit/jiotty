package net.yudichev.jiotty.user.ui.sse;

/// An [SseAdmission] that admits every stream and registers no meters, so any number of channels can share it without clashing on the meters a capped
/// budget publishes.
final class UnlimitedSseAdmission implements SseAdmission {
    public static final UnlimitedSseAdmission INSTANCE = new UnlimitedSseAdmission();

    private UnlimitedSseAdmission() {
    }

    @Override
    public boolean tryAdmit() {
        return true;
    }

    @Override
    public void release() {
    }

    @Override
    public void recordChannelFull() {
    }

    @Override
    public void recordSlowReaderDropped() {
    }
}
