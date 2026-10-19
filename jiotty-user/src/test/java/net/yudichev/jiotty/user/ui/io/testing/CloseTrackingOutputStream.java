package net.yudichev.jiotty.user.ui.io.testing;

import java.io.ByteArrayOutputStream;

/// A [ByteArrayOutputStream] that records whether it has been closed.
public final class CloseTrackingOutputStream extends ByteArrayOutputStream {
    private boolean closed;

    @Override
    public void close() {
        closed = true;
    }

    public boolean isClosed() {
        return closed;
    }
}
