package net.yudichev.jiotty.user.ui;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.user.ui.UIRequestAuthoriser.UIRequestContext;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.closeIfNotNull;
import static net.yudichev.jiotty.common.rest.HttpStatuses.METHOD_NOT_ALLOWED_405;
import static net.yudichev.jiotty.user.ui.RequestContextFilter.requestContext;

/// Serves `GET` on exactly one path as a server-sent-events stream, and threads the per-user request-context invalidation through to it, so a stream ends
/// once the token that opened it stops authorising it. A subclass says only how a stream is started.
public abstract class BaseSseApiPathHandler implements ApiPathHandler {
    private final String path;

    protected BaseSseApiPathHandler(String path) {
        this.path = checkNotNull(path, "path");
        checkArgument(path.startsWith("/"), "path must start with '/': %s", path);
    }

    @Override
    public final String pathPrefix() {
        return path;
    }

    /// @implNote two threads can act on the state below — the servlet thread running this method (T1), and the thread `onStreamClosed` fires on (T2): the
    /// channel's [SchedulingExecutor], or, once that executor has shut down, the container thread that ended the stream.
    ///
    /// Shared state:
    ///   - `streamClosed` ([AtomicBoolean]): written by T2 in `onStreamClosed`, read by T1 at the end.
    ///   - `invalidationSubscriptionRef` ([AtomicReference] of [Closeable]): [AtomicReference#set] by T1 once it has subscribed to the request context's
    /// invalidation, [AtomicReference#getAndSet] by both T1's `if (streamClosed)` branch and T2's `onStreamClosed`, via
    /// the subscription closer (CS).
    ///   - `sseStream` itself: [Closeable#close()] is idempotent, whichever thread calls it.
    ///
    /// The atomicity hinge is [AtomicReference#getAndSet] inside CS: whichever thread reaches it first claims the subscription and closes it; the loser
    /// observes `null` and [Closeable#closeIfNotNull] no-ops. The two close paths are mutually exclusive by construction. The ordering inside T2's lambda is
    /// load-bearing — it writes `streamClosed = true` *before* calling CS, so if T1 later sees `streamClosed.get() == true`, T2's [AtomicReference#getAndSet]
    /// attempt has already happened; T1's [AtomicReference#getAndSet] then succeeds exactly when T2 observed `ref == null` (i.e. T2 raced ahead of T1's
    /// [AtomicReference#set]). Cases:
    ///   1. T2 fires before T1's set → T2 CS sees null (no-op); T1 sees `streamClosed=true` and CS-closes the just-set sub. Closer: T1.
    ///   2. T2 fires after T1's set → T2 CS closes the sub; T1 sees `streamClosed=true` and CS gets null (no-op). Closer: T2.
    ///   3. T2 never fires (normal lifetime) → T1 exits with `streamClosed=false`; later invalidation fires `sseStream::close`, which routes through T2 →
    /// `onStreamClosed` → CS closes the sub. Closer: T2 (eventually).
    ///   4. T1 and T2 interleave → resolved by [AtomicReference#getAndSet] atomicity; exactly one wins the close.
    ///
    /// No path leaks the subscription (every scenario produces one close); no path double-closes ([AtomicReference#getAndSet] admits one winner). The
    /// `sseStream::close` idempotency separately handles the case where invalidation and the listener-side close race. Synchronous-invalidation edge cases
    /// (the invalidation subscription firing its callback inline on an already-invalidated context) reduce to case 1 above.
    @Override
    public final void handle(HttpServletRequest request, HttpServletResponse response) {
        if (!path.equals(request.getPathInfo())) {
            ApiServlet.writeUnknownPath(response);
            return;
        }
        if (!"GET".equals(request.getMethod())) {
            response.setStatus(METHOD_NOT_ALLOWED_405);
            return;
        }
        UIRequestContext context = requestContext(request);
        var streamClosed = new AtomicBoolean();
        var invalidationSubscriptionRef = new AtomicReference<Closeable>();
        Closeable sseStream = startSse(request, response, () -> {
            streamClosed.set(true);
            closeSubscription(invalidationSubscriptionRef);
        });
        Closeable invalidationSubscription = context.subscribeToInvalidation(sseStream::close);
        invalidationSubscriptionRef.set(invalidationSubscription);
        if (streamClosed.get()) {
            closeSubscription(invalidationSubscriptionRef);
        }
    }

    /// Starts the stream this handler serves on `request`.
    ///
    /// @param onStreamClosed run once when the stream ends
    /// @return the stream, closed when the request context is invalidated
    /// @implSpec an implementation must start `request`'s async context itself, and must run `onStreamClosed` exactly once whenever the stream ends,
    /// including when it is refused before it opens.
    protected abstract Closeable startSse(HttpServletRequest request, HttpServletResponse response, Runnable onStreamClosed);

    private static void closeSubscription(AtomicReference<Closeable> subscriptionRef) {
        closeIfNotNull(subscriptionRef.getAndSet(null));
    }
}
