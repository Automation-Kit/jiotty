package net.yudichev.jiotty.user.ui.sse.testing;

import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.lang.Json;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;

/// Reads events back out of a captured server-sent-event stream, so a test asserts on what a client actually received rather than on the wire format.
public final class SseFrames {
    private static final String DATA_PREFIX = "data: ";

    private SseFrames() {
    }

    /// The `data:` payload of the most recent `eventName` event in `frames` — the most recent one, because a channel re-sends an event every time the state
    /// behind it changes, and a test asserts on the latest.
    public static String dataOf(String frames, String eventName) {
        int eventAt = frames.lastIndexOf(eventLine(eventName));
        checkArgument(eventAt >= 0, "no %s event in the captured frames: %s", eventName, frames);
        return dataAfter(frames, eventAt, eventName);
    }

    /// [#dataOf(String,String)] deserialised as `type`.
    public static <T> T dataOf(String frames, String eventName, TypeToken<T> type) {
        return Json.parse(dataOf(frames, eventName), type);
    }

    /// The `data:` payload of every `eventName` event in `frames`, oldest first.
    public static List<String> allDataOf(String frames, String eventName) {
        String eventLine = eventLine(eventName);
        var payloads = ImmutableList.<String>builder();
        for (int eventAt = frames.indexOf(eventLine); eventAt >= 0; eventAt = frames.indexOf(eventLine, eventAt + eventLine.length())) {
            payloads.add(dataAfter(frames, eventAt, eventName));
        }
        return payloads.build();
    }

    private static String eventLine(String eventName) {
        return "event: " + eventName + '\n';
    }

    private static String dataAfter(String frames, int eventAt, String eventName) {
        int dataAt = frames.indexOf(DATA_PREFIX, eventAt);
        checkArgument(dataAt >= 0, "the %s event in the captured frames carries no data: %s", eventName, frames);
        int dataFrom = dataAt + DATA_PREFIX.length();
        int dataEnd = frames.indexOf('\n', dataFrom);
        return dataEnd < 0 ? frames.substring(dataFrom) : frames.substring(dataFrom, dataEnd);
    }
}
