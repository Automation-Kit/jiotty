package net.yudichev.jiotty.user.ui.sse.testing;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static java.nio.charset.StandardCharsets.UTF_8;

/// Keeps everything written to it, so a test can assert on the frames an SSE client received or on a handler's serialised response body.
/// [#failWrites(boolean)] stands in for a client that went away mid-response — a broken pipe or connection reset.
public final class CapturingServletOutputStream extends ServletOutputStream {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(4096);
    private boolean failWrites;

    /// Everything written so far, decoded as UTF-8 — the common case for asserting on frames or on a JSON body.
    public String output() {
        return buffer.toString(UTF_8);
    }

    /// Everything written so far, undecoded, for a response whose body is not text (a gzipped export archive).
    public byte[] bytes() {
        return buffer.toByteArray();
    }

    public void reset() {
        buffer.reset();
    }

    public void failWrites(boolean failWrites) {
        this.failWrites = failWrites;
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public void setWriteListener(WriteListener writeListener) {
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
