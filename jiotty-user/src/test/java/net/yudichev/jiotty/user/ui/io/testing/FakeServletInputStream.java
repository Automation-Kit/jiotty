package net.yudichev.jiotty.user.ui.io.testing;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;

import java.io.ByteArrayInputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;

/// A [ServletInputStream] over an in-memory body: [#setReadListener] invokes [ReadListener#onDataAvailable()] and then [ReadListener#onAllDataRead()] on the
/// calling thread, with the whole body readable at once.
public final class FakeServletInputStream extends ServletInputStream {
    private final ByteArrayInputStream delegate;

    private FakeServletInputStream(byte[] body) {
        delegate = new ByteArrayInputStream(body);
    }

    public static FakeServletInputStream of(String body) {
        return new FakeServletInputStream(body.getBytes(UTF_8));
    }

    @Override
    public int read() {
        return delegate.read();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
        return delegate.read(buffer, offset, length);
    }

    /// Delegated, so a test asserting that a capped read left the rest of the body unread measures the body.
    @Override
    public int available() {
        return delegate.available();
    }

    @Override
    public boolean isFinished() {
        return delegate.available() == 0;
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public void setReadListener(ReadListener readListener) {
        asUnchecked(() -> {
            readListener.onDataAvailable();
            readListener.onAllDataRead();
        });
    }
}
