package net.yudichev.jiotty.common.misc;

/// Throws from every notification, so tests can assert a handler fault is contained by the reporting component rather than delivered to its caller.
public final class ThrowingUpstreamHealthHandler implements UpstreamHealthHandler {
    @Override
    public void onFailure(String message, Throwable cause) {
        throw new IllegalStateException("handler bug");
    }

    @Override
    public void onSuccess() {
        throw new IllegalStateException("handler bug");
    }
}
