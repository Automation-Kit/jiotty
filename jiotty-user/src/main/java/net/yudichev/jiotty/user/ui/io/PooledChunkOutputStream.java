package net.yudichev.jiotty.user.ui.io;

import com.google.common.annotations.VisibleForTesting;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;

/// An [OutputStream] into a chain of chunks borrowed from a container's [ByteBufferPool], read back one chunk at a time. Every chunk is at most
/// [#CHUNK_SIZE], a size the pool serves from its buckets, and [#release()] returns them all to the pool.
final class PooledChunkOutputStream extends OutputStream {
    /// Within the 64 KiB a container's default pool keeps buckets for.
    @VisibleForTesting
    static final int CHUNK_SIZE = 16 * 1024;

    private final ByteBufferPool pool;
    private final boolean direct;
    /// Sized for the common case of one chunk, which every answer and most event batches fit in.
    private final List<RetainableByteBuffer.Mutable> chunks = new ArrayList<>(2);
    /// The chunk being written; `null` before the first write and after [#release()].
    private RetainableByteBuffer.@Nullable Mutable current;

    /// @param direct whether the chunks are direct buffers, which a socket write takes without a copy
    public PooledChunkOutputStream(ByteBufferPool pool, boolean direct) {
        this.pool = checkNotNull(pool, "pool");
        this.direct = direct;
    }

    @Override
    public void write(int b) {
        currentWithSpace().put((byte) b);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) {
        int position = offset;
        int remainingByteCount = length;
        while (remainingByteCount > 0) {
            RetainableByteBuffer.Mutable chunk = currentWithSpace();
            int count = (int) Math.min(remainingByteCount, chunk.space());
            chunk.put(bytes, position, count);
            position += count;
            remainingByteCount -= count;
        }
    }

    /// Reads what `input` has ready into the last chunk, borrowing a fresh one when it is full.
    ///
    /// @return the number of bytes read, or `-1` at the end of `input`
    public int readFrom(InputStream input) throws IOException {
        RetainableByteBuffer.Mutable chunk = currentWithSpace();
        ByteBuffer buffer = chunk.getByteBuffer();
        int flushPosition = BufferUtil.flipToFill(buffer);
        try {
            int count = input.read(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
            if (count > 0) {
                buffer.position(buffer.position() + count);
            }
            return count;
        } finally {
            BufferUtil.flipToFlush(buffer, flushPosition);
        }
    }

    public int chunkCount() {
        return chunks.size();
    }

    /// Returns the bytes of the chunk at `index`, positioned for reading. The buffer stays valid until [#release()].
    public ByteBuffer chunk(int index) {
        return chunks.get(index).getByteBuffer();
    }

    /// Returns a stream of the bytes held here, in order. The stream stays valid until [#release()].
    public InputStream asInputStream() {
        return new ChunksInputStream();
    }

    /// Returns every chunk to the pool. The chunks must not be in use by a write, and this must be called once.
    public void release() {
        for (RetainableByteBuffer.Mutable chunk : chunks) {
            chunk.release();
        }
        chunks.clear();
        current = null;
    }

    private RetainableByteBuffer.Mutable currentWithSpace() {
        RetainableByteBuffer.Mutable chunk = current;
        if (chunk == null || chunk.isFull()) {
            chunk = pool.acquire(CHUNK_SIZE, direct);
            chunks.add(chunk);
            current = chunk;
        }
        return chunk;
    }

    private final class ChunksInputStream extends InputStream {
        private int chunkIndex;
        private int offsetInChunk;

        @Override
        public int read() {
            while (chunkIndex < chunks.size()) {
                ByteBuffer buffer = chunk(chunkIndex);
                if (offsetInChunk < buffer.remaining()) {
                    return buffer.get(buffer.position() + offsetInChunk++) & 0xFF;
                }
                nextChunk();
            }
            return -1;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            if (length == 0) {
                return 0;
            }
            while (chunkIndex < chunks.size()) {
                ByteBuffer buffer = chunk(chunkIndex);
                int availableByteCount = buffer.remaining() - offsetInChunk;
                if (availableByteCount > 0) {
                    int count = Math.min(length, availableByteCount);
                    buffer.get(buffer.position() + offsetInChunk, bytes, offset, count);
                    offsetInChunk += count;
                    return count;
                }
                nextChunk();
            }
            return -1;
        }

        private void nextChunk() {
            chunkIndex++;
            offsetInChunk = 0;
        }
    }
}
