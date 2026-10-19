package net.yudichev.jiotty.user.ui.io;

import jakarta.servlet.WriteListener;
import org.eclipse.jetty.ee10.servlet.HttpOutput;
import org.eclipse.jetty.io.ByteBufferPool;

import java.io.IOException;
import java.nio.ByteBuffer;

import static com.google.common.base.Preconditions.checkNotNull;

/// A [ContainerStream] over a Jetty response's output. [HttpOutput#write(ByteBuffer)] holds the caller's buffer until the write completes, which is what
/// lets a pooled chunk be written as it is; it is the only write this class makes, because a `byte[]` write would start Jetty copying small writes into a
/// buffer it sends later.
final class JettyContainerStream implements ContainerStream {
    private final HttpOutput output;
    private final ByteBufferPool pool;

    JettyContainerStream(HttpOutput output, ByteBufferPool pool) {
        this.output = checkNotNull(output, "output");
        this.pool = checkNotNull(pool, "pool");
    }

    @Override
    public ByteBufferPool chunkPool() {
        return pool;
    }

    @Override
    public void setWriteListener(WriteListener listener) {
        output.setWriteListener(listener);
    }

    @Override
    public boolean isReady() {
        return output.isReady();
    }

    @Override
    public void write(ByteBuffer buffer) throws IOException {
        output.write(buffer);
    }
}
