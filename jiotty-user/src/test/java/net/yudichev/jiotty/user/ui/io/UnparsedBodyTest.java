package net.yudichev.jiotty.user.ui.io;

import com.fasterxml.jackson.databind.ObjectReader;
import net.yudichev.jiotty.common.lang.Json;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class UnparsedBodyTest {
    private static final ObjectReader READER = Json.createReaderFor(Note.class);

    private final ArrayByteBufferPool.Tracking pool = new ArrayByteBufferPool.Tracking();

    @AfterEach
    void tearDown() {
        assertThat(pool.getLeaks()).as("every chunk returned to the pool").isEmpty();
    }

    @Test
    void parsesABodyInOneChunk() {
        UnparsedBody body = bodyOf(Json.stringify(new Note("short")));

        assertThat(body.<Note>parseAs(READER)).isEqualTo(new ReceivedBody.Parsed<>(new Note("short")));
        body.release();
    }

    @Test
    void parsesABodySpanningSeveralChunks() {
        String text = "x".repeat(PooledChunkOutputStream.CHUNK_SIZE * 2);
        UnparsedBody body = bodyOf(Json.stringify(new Note(text)));

        assertThat(body.<Note>parseAs(READER)).isEqualTo(new ReceivedBody.Parsed<>(new Note(text)));
        body.release();
    }

    @Test
    void aBodyThatIsNotJsonIsMalformed() {
        UnparsedBody body = bodyOf("not json");

        assertThat(body.<Note>parseAs(READER)).isInstanceOf(ReceivedBody.Malformed.class);
        body.release();
    }

    @Test
    void aBodyOfTheJsonLiteralNullIsMalformed() {
        UnparsedBody body = bodyOf("null");

        assertThat(body.<Note>parseAs(READER)).isInstanceOf(ReceivedBody.Malformed.class);
        body.release();
    }

    @Test
    void parsesABodyWithAParserOfTheCallers() {
        UnparsedBody body = bodyOf("a=b");

        assertThat(body.parseWith(input -> new String(input.readAllBytes(), UTF_8))).isEqualTo(new ReceivedBody.Parsed<>("a=b"));
        body.release();
    }

    @Test
    void aParserThatFailsMakesTheBodyMalformed() {
        UnparsedBody body = bodyOf("a=b");

        assertThat(body.parseWith(_ -> {
            throw new IOException("not a form");
        })).isInstanceOf(ReceivedBody.Malformed.class);
        body.release();
    }

    @Test
    void theEmptyAndTooLargeBodiesParseToTheirOutcomes() {
        assertThat(UnparsedBody.EMPTY.<Note>parseAs(READER)).isInstanceOf(ReceivedBody.Empty.class);
        assertThat(UnparsedBody.TOO_LARGE.<Note>parseAs(READER)).isInstanceOf(ReceivedBody.TooLarge.class);
    }

    /// The second release finds nothing left to return, and the leak check confirms the chunks went back once.
    @Test
    void releasingTwiceReturnsTheChunksOnce() {
        UnparsedBody body = bodyOf(Json.stringify(new Note("once")));

        body.release();
        body.release();
    }

    private UnparsedBody bodyOf(String text) {
        var chunks = new PooledChunkOutputStream(pool, false);
        byte[] bytes = text.getBytes(UTF_8);
        chunks.write(bytes, 0, bytes.length);
        return UnparsedBody.of(chunks);
    }

    private record Note(String text) {}
}
