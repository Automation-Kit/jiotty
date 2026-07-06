package net.yudichev.jiotty.common.lang;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public abstract class BaseIdempotentCloseable implements Closeable {
    private final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public final void close() {
        if (!closed.getAndSet(true)) {
            doClose();
        }
    }

    public final boolean isClosed() {
        return closed.get();
    }

    /// Same as [#isClosed()] but with [plain][AtomicReference#getPlain()] read, only guaranteed to work if called from the same thead that calls [#close()].
    public final boolean isClosedPlain() {
        return closed.getPlain();
    }

    /// Same as [#isClosed()] but with an [opaque][AtomicBoolean#getOpaque()] read instead of a full volatile (acquire) one.
    public final boolean isClosedOpaque() {
        return closed.getOpaque();
    }

    protected abstract void doClose();
}
