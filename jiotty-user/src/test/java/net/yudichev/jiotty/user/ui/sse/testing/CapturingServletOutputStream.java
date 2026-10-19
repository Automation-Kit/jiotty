package net.yudichev.jiotty.user.ui.sse.testing;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.user.ui.io.ContainerStream;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Set;

import static com.google.common.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.UTF_8;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;

/// A [ServletOutputStream] that records everything written to it and models a container's non-blocking output: registering a [WriteListener] invokes its
/// [WriteListener#onWritePossible()] at once, and [#stallReading()], [#resumeReading()] and [#failHeldWrite(Throwable)] hold, release or fail a write.
public final class CapturingServletOutputStream extends ServletOutputStream implements ContainerStream {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(4096);
    private final ArrayByteBufferPool.Tracking pool = new ArrayByteBufferPool.Tracking();
    private @Nullable WriteListener writeListener;
    private boolean failWrites;
    private boolean readingStalled;
    /// A write the client is not reading, `null` when there is none.
    private @Nullable ByteBuffer heldWrite;

    /// Everything written so far, decoded as UTF-8 — the common case for asserting on frames or on a JSON body.
    public String output() {
        return buffer.toString(UTF_8);
    }

    /// Returns the `error` code of the error envelope written so far.
    public String errorCode() {
        return Json.parse(output()).path("error").asText();
    }

    /// Everything written so far, undecoded, for a response whose body is not text (a gzipped export archive).
    public byte[] bytes() {
        return buffer.toByteArray();
    }

    public void reset() {
        buffer.reset();
    }

    /// Fails every later write, as a client that went away mid-response does: a broken pipe or a connection reset.
    public void failWrites(boolean failWrites) {
        this.failWrites = failWrites;
    }

    /// Holds the next write unread, as a client that has stopped reading does: from that write until [#resumeReading()], [#isReady()] returns `false`.
    public void stallReading() {
        readingStalled = true;
    }

    /// Captures the write being held, if any, and invokes the listener's [WriteListener#onWritePossible()].
    public void resumeReading() {
        readingStalled = false;
        ByteBuffer heldBuffer = heldWrite;
        if (heldBuffer != null) {
            heldWrite = null;
            capture(heldBuffer);
            asUnchecked(listener()::onWritePossible);
        }
    }

    /// Invokes [WriteListener#onWritePossible()] while a write is held, as a container does when the call that [#setWriteListener(WriteListener)] triggers
    /// arrives after a drain has started writing.
    public void callBackWhileAWriteIsHeld() {
        checkState(heldWrite != null, "no write is being held");
        asUnchecked(listener()::onWritePossible);
    }

    /// Fails the write being held, as the container does when the client is gone or its idle timeout expires.
    public void failHeldWrite(Throwable failure) {
        checkState(heldWrite != null, "no write is being held");
        heldWrite = null;
        listener().onError(failure);
    }

    private WriteListener listener() {
        WriteListener listener = writeListener;
        assert listener != null : "a write is held only once a listener is set";
        return listener;
    }

    /// Returns the pooled buffers taken and not released, which must be empty once every exchange written here has ended.
    public Set<ArrayByteBufferPool.Tracking.TrackedBuffer> leakedBuffers() {
        return pool.getLeaks();
    }

    @Override
    public ByteBufferPool chunkPool() {
        return pool;
    }

    @Override
    public boolean isReady() {
        return heldWrite == null;
    }

    @Override
    public void setWriteListener(WriteListener writeListener) {
        checkState(this.writeListener == null, "a write listener is set already");
        this.writeListener = writeListener;
        asUnchecked(writeListener::onWritePossible);
    }

    @Override
    public void write(ByteBuffer bytes) throws IOException {
        failIfBroken();
        checkState(heldWrite == null, "a write is pending");
        if (readingStalled) {
            heldWrite = bytes;
        } else {
            capture(bytes);
        }
    }

    private void capture(ByteBuffer bytes) {
        ByteBuffer view = bytes.duplicate();
        while (view.hasRemaining()) {
            buffer.write(view.get());
        }
    }

    @Override
    public void write(int b) throws IOException {
        failIfBroken();
        buffer.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        failIfBroken();
        buffer.write(b, off, len);
    }

    @Override
    public void print(String s) throws IOException {
        failIfBroken();
        byte[] bytes = s.getBytes(UTF_8);
        buffer.write(bytes, 0, bytes.length);
    }

    @Override
    public void flush() {
    }

    private void failIfBroken() throws IOException {
        if (failWrites) {
            throw new IOException("broken pipe");
        }
    }
}
