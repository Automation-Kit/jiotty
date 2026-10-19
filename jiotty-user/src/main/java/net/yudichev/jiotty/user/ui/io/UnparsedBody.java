package net.yudichev.jiotty.user.ui.io;

import com.fasterxml.jackson.databind.ObjectReader;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static com.google.common.base.Preconditions.checkNotNull;

/// A request body read in full into pooled chunks, parsed on demand by [#parseAs]. [#release()] must run exactly once whatever becomes of the body, and
/// the body must be used on one thread at a time.
public final class UnparsedBody {
    static final UnparsedBody EMPTY = new UnparsedBody(null, false);
    static final UnparsedBody TOO_LARGE = new UnparsedBody(null, true);
    private final boolean tooLarge;
    /// `null` once released, and for a body that was empty or too large.
    private @Nullable PooledChunkOutputStream chunks;

    /// @param chunks   the body's bytes; `null` for a body that was empty or too large
    /// @param tooLarge whether the body ran past the most that was read
    private UnparsedBody(@Nullable PooledChunkOutputStream chunks, boolean tooLarge) {
        this.chunks = chunks;
        this.tooLarge = tooLarge;
    }

    static UnparsedBody of(PooledChunkOutputStream chunks) {
        return new UnparsedBody(chunks, false);
    }

    /// Parses the body as JSON with `reader`. May be called more than once before [#release()].
    ///
    /// @param <T> the type the body parses to
    public <T> ReceivedBody<T> parseAs(ObjectReader reader) {
        // A body in one heap chunk, the usual case, parses from the chunk's own array.
        return parseChunks(heldChunks -> heldChunks.chunkCount() == 1 ? parse(reader, heldChunks.chunk(0)) : reader.readValue(heldChunks.asInputStream()));
    }

    /// Parses the body with `parser`, for a body in a format other than JSON. May be called more than once before [#release()].
    ///
    /// @param <T> the type the body parses to
    public <T> ReceivedBody<T> parseWith(Parser<? extends T> parser) {
        checkNotNull(parser, "parser");
        return parseChunks(heldChunks -> parser.parse(heldChunks.asInputStream()));
    }

    private <T> ReceivedBody<T> parseChunks(ChunkParser<T> parser) {
        if (tooLarge) {
            return ReceivedBody.TooLarge.instance();
        }
        PooledChunkOutputStream heldChunks = chunks;
        // No chunks here means the body was empty.
        if (heldChunks == null) {
            return ReceivedBody.Empty.instance();
        }
        try {
            T value = parser.parse(heldChunks);
            return value == null ? new ReceivedBody.Malformed<>(new IOException("the body parses to null")) : new ReceivedBody.Parsed<>(value);
        } catch (IOException e) {
            return new ReceivedBody.Malformed<>(e);
        }
    }

    /// @return the parsed value; `null` when the body is the JSON literal `null`
    private static <T> @Nullable T parse(ObjectReader reader, ByteBuffer chunk) throws IOException {
        return reader.readValue(chunk.array(), chunk.arrayOffset() + chunk.position(), chunk.remaining());
    }

    /// Returns the body's chunks to the pool.
    public void release() {
        PooledChunkOutputStream heldChunks = chunks;
        if (heldChunks != null) {
            chunks = null;
            heldChunks.release();
        }
    }

    /// Parses a body in a format of the caller's.
    ///
    /// @param <T> the type the body parses to
    public interface Parser<T> {
        /// @param body the body's bytes, read on the calling thread
        /// @throws IOException if the bytes are not a body of this format
        T parse(InputStream body) throws IOException;
    }

    private interface ChunkParser<T> {
        /// @return the parsed value; `null` when the body denotes none, such as the JSON literal `null`
        @Nullable T parse(PooledChunkOutputStream chunks) throws IOException;
    }
}
