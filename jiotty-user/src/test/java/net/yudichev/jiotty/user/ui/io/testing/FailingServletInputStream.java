package net.yudichev.jiotty.user.ui.io.testing;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;

import java.io.IOException;

import static com.google.common.base.Preconditions.checkNotNull;

/// A [ServletInputStream] whose [#setReadListener] invokes the listener's [ReadListener#onError] with the given failure at once, delivering no data, as
/// when the client disconnects mid-upload.
public final class FailingServletInputStream extends ServletInputStream {
    private final IOException failure;

    public FailingServletInputStream(IOException failure) {
        this.failure = checkNotNull(failure, "failure");
    }

    @Override
    public int read() {
        return -1;
    }

    @Override
    public boolean isFinished() {
        return false;
    }

    @Override
    public boolean isReady() {
        return false;
    }

    @Override
    public void setReadListener(ReadListener readListener) {
        readListener.onError(failure);
    }
}
