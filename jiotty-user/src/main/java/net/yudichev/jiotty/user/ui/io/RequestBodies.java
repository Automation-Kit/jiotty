package net.yudichev.jiotty.user.ui.io;

import com.fasterxml.jackson.databind.ObjectReader;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/// Static methods that read request bodies as their bytes arrive, on container threads, into chunks of the container's own buffer pool.
public final class RequestBodies {
    private RequestBodies() {
    }

    /// Reads the body of `exchange`'s request and parses it with `reader`, on the container thread that reads its last bytes.
    ///
    /// @param <T>      the type the body parses to
    /// @param maxBytes the longest body read; a longer one is [ReceivedBody.TooLarge]
    /// @param reader   the reader that parses the body; one for a type known at compile time should be built once and shared
    /// @return the body as parsed, or what made it unusable: absent, longer than `maxBytes`, or malformed
    public static <T> CompletableFuture<ReceivedBody<T>> read(AsyncExchange exchange, int maxBytes, ObjectReader reader) {
        checkNotNull(reader, "reader");
        return receive(exchange, maxBytes).thenApply(body -> {
            try {
                return body.parseAs(reader);
            } finally {
                body.release();
            }
        });
    }

    /// Reads the body of `exchange`'s request, without parsing it. Must be called while dispatching the request, before anything claims the exchange.
    ///
    /// @param maxBytes the longest body read; a longer one parses as [ReceivedBody.TooLarge]
    /// @return the body as read; the caller must [release][UnparsedBody#release()] it
    public static CompletableFuture<UnparsedBody> receive(AsyncExchange exchange, int maxBytes) {
        checkArgument(maxBytes > 0, "maxBytes must be positive: %s", maxBytes);
        ServletRequest request = exchange.request();
        if (request.getContentLengthLong() == 0) {
            return CompletableFuture.completedFuture(UnparsedBody.EMPTY);
        }
        exchange.bodyReceiving();
        try {
            ServletInputStream input = request.getInputStream();
            // Heap chunks, because the parser reads them as arrays and no socket write takes them.
            var chunks = new PooledChunkOutputStream(ContainerStream.of(request, exchange.response()).chunkPool(), false);
            var bodyReader = new BodyReader(input, chunks, maxBytes);
            // The container invokes no ReadListener callback when the exchange ends first, as when its deadline answers it.
            exchange.whenEnded(() -> bodyReader.fail(new IOException("the exchange ended before its body was read")));
            input.setReadListener(bodyReader);
            // The derived future, so the body counts as received before any dependent the caller attaches runs.
            return bodyReader.result.whenComplete((_, _) -> exchange.bodyReceived());
        } catch (IOException | RuntimeException e) {
            exchange.bodyReceived();
            return CompletableFuture.failedFuture(e);
        }
    }

    /// A [ReadListener] the container invokes one callback at a time, so its fields need no guard. [#fail(Throwable)] also runs once the exchange has ended,
    /// after the container's last callback.
    private static final class BodyReader implements ReadListener {
        private final CompletableFuture<UnparsedBody> result = new CompletableFuture<>();
        private final ServletInputStream input;
        private final PooledChunkOutputStream chunks;
        private final int maxBytes;
        private long bytesRead;

        BodyReader(ServletInputStream input, PooledChunkOutputStream chunks, int maxBytes) {
            this.input = checkNotNull(input, "input");
            this.chunks = checkNotNull(chunks, "chunks");
            assert maxBytes > 0 : "receive() checks maxBytes";
            this.maxBytes = maxBytes;
        }

        @Override
        public void onDataAvailable() throws IOException {
            while (!result.isDone() && input.isReady()) {
                int count = chunks.readFrom(input);
                if (count < 0) {
                    return;
                }
                bytesRead += count;
                if (bytesRead > maxBytes) {
                    chunks.release();
                    result.complete(UnparsedBody.TOO_LARGE);
                }
            }
        }

        @Override
        public void onAllDataRead() {
            if (!result.isDone()) {
                if (bytesRead == 0) {
                    chunks.release();
                    result.complete(UnparsedBody.EMPTY);
                } else {
                    result.complete(UnparsedBody.of(chunks));
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {
            fail(throwable);
        }

        /// Ends a read still in progress; one that has already ended is left as it ended.
        void fail(Throwable throwable) {
            if (!result.isDone()) {
                chunks.release();
                result.completeExceptionally(throwable);
            }
        }
    }
}
