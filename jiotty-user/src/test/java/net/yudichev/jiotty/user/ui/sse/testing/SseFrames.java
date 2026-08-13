package net.yudichev.jiotty.user.ui.sse.testing;

import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.lang.Json;

import static com.google.common.base.Preconditions.checkArgument;

/// Reads events back out of a captured server-sent-event stream, so a test asserts on what a client actually received rather than on the wire format.
public final class SseFrames {
    private static final String DATA_PREFIX = "data: ";

    private SseFrames() {
    }

    /// The `data:` payload of the most recent `eventName` event in `frames` — the most recent one, because a channel re-sends an event every time the state
    /// behind it changes, and a test asserts on the latest.
    public static String dataOf(String frames, String eventName) {
        int eventAt = frames.lastIndexOf("event: " + eventName + '\n');
        checkArgument(eventAt >= 0, "no %s event in the captured frames: %s", eventName, frames);
        int dataAt = frames.indexOf(DATA_PREFIX, eventAt);
        checkArgument(dataAt >= 0, "the %s event in the captured frames carries no data: %s", eventName, frames);
        int dataFrom = dataAt + DATA_PREFIX.length();
        int dataEnd = frames.indexOf('\n', dataFrom);
        return dataEnd < 0 ? frames.substring(dataFrom) : frames.substring(dataFrom, dataEnd);
    }

    /// [#dataOf(String,String)] deserialised as `type`.
    public static <T> T dataOf(String frames, String eventName, TypeToken<T> type) {
        return Json.parse(dataOf(frames, eventName), type);
    }
}
