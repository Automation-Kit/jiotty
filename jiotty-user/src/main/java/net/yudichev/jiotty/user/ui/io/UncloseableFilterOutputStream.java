package net.yudichev.jiotty.user.ui.io;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import static com.google.common.base.Preconditions.checkNotNull;

/// A [FilterOutputStream] that passes every write through to the stream it wraps, and leaves that stream open when [closed][#close].
final class UncloseableFilterOutputStream extends FilterOutputStream {
    public UncloseableFilterOutputStream(OutputStream output) {
        super(checkNotNull(output, "output"));
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        out.write(bytes, offset, length);
    }

    @Override
    public void close() {
    }
}
