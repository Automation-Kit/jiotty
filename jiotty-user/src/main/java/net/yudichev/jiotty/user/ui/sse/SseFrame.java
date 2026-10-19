package net.yudichev.jiotty.user.ui.sse;

import com.fasterxml.jackson.databind.ObjectWriter;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.user.ui.io.ResponseBody;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;

/// One server-sent event, encoded on the thread that writes it. A frame supersedes an unsent earlier one with the same key, so a client that reads slowly
/// gets only the latest state.
// HardcodedLineSeparator: the SSE wire format specifies LF, so the platform separator would corrupt the frames on any host that does not use it.
@SuppressWarnings("HardcodedLineSeparator")
final class SseFrame implements ResponseBody {
    /// The SSE `retry` field setting the client's reconnection delay, written once ahead of the first event of a stream.
    private static final byte[] RETRY_PREAMBLE = "retry: 3000\n\n".getBytes(UTF_8);
    private static final byte[] DATA_PREFIX = "data: ".getBytes(UTF_8);
    private static final byte[] FRAME_END = "\n\n".getBytes(UTF_8);
    /// One entry per event name a feature sends, which is a fixed set of constants. Filled and read by [#writeTo] on the container threads draining every
    /// client of every channel at once, which no one thread owns.
    private static final ConcurrentMap<String, byte[]> EVENT_LINES_BY_EVENT_NAME = new ConcurrentHashMap<>();

    private final String eventName;
    /// `null` for a frame nothing supersedes.
    private final @Nullable String key;
    private final Object data;
    private final ObjectWriter writer;
    private final boolean opensStream;

    /// @param key         what this frame supersedes; `null` for a frame nothing supersedes
    /// @param data        the event's payload, which must be immutable: a container thread serialises it after the send has returned
    /// @param opensStream whether this frame is the first a stream carries
    private SseFrame(String eventName, @Nullable String key, Object data, ObjectWriter writer, boolean opensStream) {
        this.eventName = checkNotNull(eventName, "eventName");
        this.key = key;
        this.data = checkNotNull(data, "data");
        this.writer = checkNotNull(writer, "writer");
        this.opensStream = opensStream;
    }

    /// Creates a frame that supersedes an unsent earlier one carrying the same `key`.
    public static SseFrame createSuperseding(String eventName, String key, Object data, ObjectWriter writer) {
        return new SseFrame(eventName, checkNotNull(key, "key"), data, writer, false);
    }

    /// Creates a frame that is sent whatever follows it.
    public static SseFrame createUnsuperseded(String eventName, Object data, ObjectWriter writer) {
        return new SseFrame(eventName, null, data, writer, false);
    }

    /// Creates the first frame of a stream, which carries the reconnection delay ahead of the event.
    public static SseFrame createOpening(String eventName, Object data, ObjectWriter writer) {
        return new SseFrame(eventName, null, data, writer, true);
    }

    /// Returns whether this frame makes the unsent `older` one redundant.
    public boolean supersedes(SseFrame older) {
        return key != null && key.equals(older.key);
    }

    @Override
    public void applyHeadersTo(HttpServletResponse response) {
    }

    @Override
    public void writeTo(OutputStream output) throws IOException {
        if (opensStream) {
            output.write(RETRY_PREAMBLE);
        }
        output.write(EVENT_LINES_BY_EVENT_NAME.computeIfAbsent(eventName, name -> ("event: " + name + '\n').getBytes(UTF_8)));
        output.write(DATA_PREFIX);
        writer.writeValue(output, data);
        output.write(FRAME_END);
    }
}
