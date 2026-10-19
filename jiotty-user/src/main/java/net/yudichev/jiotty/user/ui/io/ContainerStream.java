package net.yudichev.jiotty.user.ui.io;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.HttpOutput;
import org.eclipse.jetty.ee10.servlet.ServletContextRequest;
import org.eclipse.jetty.io.ByteBufferPool;

import java.io.IOException;
import java.nio.ByteBuffer;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/// The non-blocking side of one response's output: one [ByteBuffer] written at a time, with [WriteListener] callbacks signalling when the next write may
/// start.
///
/// @implSpec [#write] must hold `buffer` until the write completes — until [#isReady()] next returns `true`, or the listener is next invoked — and must not
/// copy it into a buffer it sends later.
public interface ContainerStream {
    /// Returns the pool the container takes its own buffers from, which the chunks written here are taken from too.
    ByteBufferPool chunkPool();

    void setWriteListener(WriteListener listener);

    boolean isReady();

    /// Starts writing `buffer`. May be called only when [#isReady()] last returned `true`, or from [WriteListener#onWritePossible()].
    ///
    /// @throws IOException if the response cannot be written, as when the client has gone
    void write(ByteBuffer buffer) throws IOException;

    /// Returns the stream of `response`, the response to `request`.
    ///
    /// @throws IOException if the response's output stream cannot be opened
    static ContainerStream of(ServletRequest request, HttpServletResponse response) throws IOException {
        ServletOutputStream output = response.getOutputStream();
        if (output instanceof ContainerStream stream) {
            return stream;
        }
        checkState(output instanceof HttpOutput, "a response served by Jetty is required, got an output stream of %s", output.getClass());
        ServletContextRequest servletContextRequest = checkNotNull(ServletContextRequest.getServletContextRequest(request), "a Jetty request is required");
        return new JettyContainerStream((HttpOutput) output, servletContextRequest.getComponents().getByteBufferPool());
    }
}
