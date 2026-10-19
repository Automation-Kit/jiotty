package net.yudichev.jiotty.user.ui.io;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static org.assertj.core.api.Assertions.assertThat;

// IOResourceOpenedButNotSafelyClosed: release() returns the chunks to the pool, and the leak check after each test asserts it ran.
@SuppressWarnings("IOResourceOpenedButNotSafelyClosed")
class PooledChunkOutputStreamTest {
    private final ArrayByteBufferPool.Tracking pool = new ArrayByteBufferPool.Tracking();

    @AfterEach
    void tearDown() {
        assertThat(pool.getLeaks()).as("every chunk returned to the pool").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void holdsWhatIsWrittenInChunksNoLargerThanTheChunkSize(boolean direct) {
        var chunks = new PooledChunkOutputStream(pool, direct);
        byte[] bytes = "abcdefgh".repeat(PooledChunkOutputStream.CHUNK_SIZE / 4).getBytes(UTF_8);

        chunks.write(bytes, 0, bytes.length);
        chunks.write('!');

        assertThat(chunks.chunkCount()).isEqualTo(3);
        for (int i = 0; i < chunks.chunkCount(); i++) {
            assertThat(chunks.chunk(i).remaining()).isLessThanOrEqualTo(PooledChunkOutputStream.CHUNK_SIZE);
        }
        assertThat(contentOf(chunks)).isEqualTo(new String(bytes, UTF_8) + '!');
        chunks.release();
    }

    @Test
    void readsBackThroughAStreamAcrossChunks() {
        var chunks = new PooledChunkOutputStream(pool, true);
        String text = "x".repeat(PooledChunkOutputStream.CHUNK_SIZE + 5);
        byte[] bytes = text.getBytes(UTF_8);
        chunks.write(bytes, 0, bytes.length);

        var copy = new ByteArrayOutputStream();
        asUnchecked(() -> chunks.asInputStream().transferTo(copy));

        assertThat(copy.toString(UTF_8)).isEqualTo(text);
        chunks.release();
    }

    @Test
    void readsBackOneByteAtATimeAcrossChunks() {
        var chunks = new PooledChunkOutputStream(pool, true);
        String text = "x".repeat(PooledChunkOutputStream.CHUNK_SIZE) + "yz";
        byte[] bytes = text.getBytes(UTF_8);
        chunks.write(bytes, 0, bytes.length);
        InputStream input = chunks.asInputStream();

        var copy = new StringBuilder();
        int next;
        while ((next = getAsUnchecked(input::read)) >= 0) {
            copy.append((char) next);
        }

        assertThat(copy.toString()).isEqualTo(text);
        chunks.release();
    }

    @Test
    void readsNothingIntoAnEmptyRange() {
        var chunks = new PooledChunkOutputStream(pool, true);
        chunks.write('a');

        assertThat(getAsUnchecked(() -> chunks.asInputStream().read(new byte[4], 0, 0))).isZero();
        chunks.release();
    }

    @Test
    void readsAStreamIntoHeapChunks() {
        var chunks = new PooledChunkOutputStream(pool, false);
        String text = "y".repeat(PooledChunkOutputStream.CHUNK_SIZE + 3);
        var input = new ByteArrayInputStream(text.getBytes(UTF_8));

        int total = 0;
        int count;
        while ((count = getAsUnchecked(() -> chunks.readFrom(input))) >= 0) {
            total += count;
        }

        assertThat(total).isEqualTo(text.length());
        assertThat(contentOf(chunks)).isEqualTo(text);
        chunks.release();
    }

    @Test
    void holdsNothingUntilWrittenTo() {
        var chunks = new PooledChunkOutputStream(pool, true);

        assertThat(chunks.chunkCount()).isZero();
        assertThat(getAsUnchecked(() -> chunks.asInputStream().read())).isEqualTo(-1);
        chunks.release();
    }

    private static String contentOf(PooledChunkOutputStream chunks) {
        var content = new StringBuilder();
        for (int i = 0; i < chunks.chunkCount(); i++) {
            ByteBuffer chunk = chunks.chunk(i);
            content.append(UTF_8.decode(chunk.duplicate()));
        }
        return content.toString();
    }
}
