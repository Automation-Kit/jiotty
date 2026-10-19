package net.yudichev.jiotty.user.ui.sse;

import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.reflect.TypeToken;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.lang.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static net.yudichev.jiotty.user.ui.io.testing.ResponseBodies.encode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SseFrameTest {
    private static final ObjectWriter WRITER = Json.createWriterFor(new TypeToken<>() {});

    @Test
    void encodesTheEventAndItsDataAsOneFrame() {
        assertThat(encode(SseFrame.createUnsuperseded("update", Map.of("value", "v"), WRITER))).isEqualTo("event: update\ndata: {\"value\":\"v\"}\n\n");
    }

    /// react-native-sse takes its reconnect interval from the stream, so the stream's first frame carries it.
    @Test
    void anOpeningFrameCarriesTheReconnectDelayAheadOfItsEvent() {
        assertThat(encode(SseFrame.createOpening("hello", Map.of(), WRITER))).isEqualTo("retry: 3000\n\nevent: hello\ndata: {}\n\n");
    }

    @Test
    void aFrameSupersedesOnlyAFrameWithItsKey() {
        SseFrame newerFrame = SseFrame.createSuperseding("user-update", "user:1", 2, WRITER);

        assertThat(newerFrame.supersedes(SseFrame.createSuperseding("user-removed", "user:1", 1, WRITER))).isTrue();
        assertThat(newerFrame.supersedes(SseFrame.createSuperseding("user-update", "user:2", 1, WRITER))).isFalse();
        assertThat(newerFrame.supersedes(SseFrame.createUnsuperseded("user-update", 1, WRITER))).isFalse();
    }

    @Test
    void anUnsupersededFrameSupersedesNothing() {
        assertThat(SseFrame.createUnsuperseded("ticket-removed", 2, WRITER).supersedes(SseFrame.createUnsuperseded("ticket-removed", 1, WRITER))).isFalse();
    }

    /// A stream's headers are written once, as it opens; a frame written into it has none of its own.
    @Test
    void setsNoHeaders(@Mock HttpServletResponse response) {
        SseFrame.createUnsuperseded("update", 1, WRITER).applyHeadersTo(response);

        verifyNoInteractions(response);
    }
}
