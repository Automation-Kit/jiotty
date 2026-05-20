package net.yudichev.jiotty.user.ui;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.lang.Closeable;

import java.io.IOException;

/// Per-[UIServer] hub for server-sent-event streams. Each call to [#startSse] adds a new client; the service is responsible for keeping connected clients alive
/// (heartbeats) and pushing event payloads such as `options-update` and `displayable-update` to every connected client until each stream is closed.
public interface SseService {
    /// Opens a new SSE stream on the request. Returns a [Closeable] that closes the stream and removes the client from the broadcast set. `onStreamClosed` runs
    /// (once) when the stream is closed by either side.
    Closeable startSse(HttpServletRequest request, HttpServletResponse response, Runnable onStreamClosed) throws IOException;
}
