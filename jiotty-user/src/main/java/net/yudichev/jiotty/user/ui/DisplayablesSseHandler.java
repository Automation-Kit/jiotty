package net.yudichev.jiotty.user.ui;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.user.ui.UIRequestAuthoriser.UIRequestContext;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.user.ui.RequestContextFilter.requestContext;

/// Handles `GET /ui/api/displayables/stream` — opens a server-sent-events stream and threads the per-user request-context invalidation through to it.
public final class DisplayablesSseHandler implements ApiPathHandler {
    static final String PATH = "/displayables/stream";

    private final SseService sseService;

    @Inject
    public DisplayablesSseHandler(SseService sseService) {
        this.sseService = checkNotNull(sseService, "sseService");
    }

    @Override
    public String pathPrefix() {
        return PATH;
    }

    /// @implNote three threads can act on the state below — the servlet thread running this method (T1), the [UIExecutor] [SchedulingExecutor] inside
    /// [SseService] on which `onStreamClosed` fires (T2), and Jetty's async I/O thread for `AsyncListener` callbacks (which always trampolines to T2 via
    /// `executor.execute(...)`, so it collapses into T2 for the purposes of this analysis).
    ///
    /// Shared state:
    ///   - `streamClosed` (`AtomicBoolean`): written by T2 in `onStreamClosed`, read by T1 at the end.
    ///   - `invalidationSubscriptionRef` (`AtomicReference<Closeable>`): `set` by T1 after `subscribeToInvalidation`, `getAndSet(null)` by both T1's `if
    /// (streamClosed)` branch and T2's `onStreamClosed`, via [#closeSubscription].
    ///   - `sseStream` itself: `close()` is idempotent (`SseClient.close` guards with a `closed` flag).
    ///
    /// The atomicity hinge is `getAndSet(null)` inside [#closeSubscription]: whichever thread reaches it first claims the subscription and closes it; the loser
    /// observes `null` and `closeIfNotNull` no-ops. The two close paths are mutually exclusive by construction. The ordering inside T2's lambda is load-bearing
    /// — it writes `streamClosed = true` *before* calling `closeSubscription(ref)`, so if T1 later sees `streamClosed.get() == true`, T2's `getAndSet` attempt
    /// has already happened; T1's `getAndSet` then succeeds exactly when T2 observed `ref == null` (i.e. T2 raced ahead of T1's `set`). Cases (CS =
    /// `closeSubscription`):
    ///   1. T2 fires before T1's `set`        → T2 CS sees null (no-op); T1 sees `streamClosed=true` and CS-closes the just-`set` sub. Closer: T1.
    ///   2. T2 fires after T1's `set`         → T2 CS closes the sub; T1 sees `streamClosed=true` and CS gets null (no-op). Closer: T2.
    ///   3. T2 never fires (normal lifetime)  → T1 exits with `streamClosed=false`; later invalidation fires `sseStream::close`, which routes through T2 →
    /// `onStreamClosed` → CS closes the sub. Closer: T2 (eventually).
    ///   4. T1 and T2 interleave              → resolved by `getAndSet` atomicity; exactly one wins the close.
    ///
    /// No path leaks the subscription (every scenario produces one close); no path double-closes (the `getAndSet` admits one winner). The `sseStream::close`
    /// idempotency separately handles the case where invalidation and the listener-side close race. Synchronous-invalidation edge cases
    /// (`subscribeToInvalidation` firing its callback inline on an already-invalidated context) reduce to case 1 above.
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) {
        if (!PATH.equals(request.getPathInfo())) {
            ApiServlet.writeUnknownPath(response);
            return;
        }
        if (!"GET".equals(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        asUnchecked(() -> {
            UIRequestContext context = requestContext(request);
            var streamClosed = new AtomicBoolean();
            var invalidationSubscriptionRef = new AtomicReference<Closeable>();
            Closeable sseStream = sseService.startSse(request, response, () -> {
                streamClosed.set(true);
                closeSubscription(invalidationSubscriptionRef);
            });
            Closeable invalidationSubscription = context.subscribeToInvalidation(sseStream::close);
            invalidationSubscriptionRef.set(invalidationSubscription);
            if (streamClosed.get()) {
                closeSubscription(invalidationSubscriptionRef);
            }
        });
    }

    private static void closeSubscription(AtomicReference<Closeable> subscriptionRef) {
        Closeable.closeIfNotNull(subscriptionRef.getAndSet(null));
    }
}
