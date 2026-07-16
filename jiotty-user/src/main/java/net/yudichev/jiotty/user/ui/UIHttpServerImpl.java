package net.yudichev.jiotty.user.ui;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.inject.BindingAnnotation;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.EventsHandler;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;

/// Thin Jetty host. Composes all injected [ServletMount]s into a single [ContextHandlerCollection] (which routes by longest matching context path) and installs
/// [JsonErrorHandler] as the server-wide error handler so requests not matching any mount return a JSON `{"error":"Not found"}` envelope via
/// [Response#writeError].
///
/// The handler chain is wrapped in an [EventsHandler] subclass that publishes two request-level timers to Micrometer, tagged by `method`, `status`, and
/// `path`:
///   - `http_response_begin_seconds` — recorded when Jetty fires the `onResponseBegin` callback, i.e. at the moment the response is committed (TTFB).
///     Meaningful for streaming endpoints (SSE) as well as buffered ones — it captures the time the server spends between accepting the request and writing
///     the first response byte.
///   - `http_request_seconds` — recorded when Jetty fires `onComplete` at full request completion. For non-streaming endpoints it mirrors
///     `http_response_begin_seconds`; for SSE it measures session length, which is informational rather than a TTFB signal.
///
/// The `path` tag is derived from the request attribute named [#ROUTE_NAME_ATTRIBUTE] when present (set by [UIServerRuntime#dispatchApiPath] to the matched
/// [ApiPathHandler#pathPrefix]). When the attribute is absent, the tag falls back to the first URL path segment of the request **if** that segment is one of
/// the [ServletMount] context paths registered with the server; any other path (browser auto-requests like `/favicon.ico`, probes, typos) collapses into a
/// single `"unmatched"` bucket, and the root `/` stays as `/`. Tag values are drawn from this bounded identifier set only.
final class UIHttpServerImpl extends BaseLifecycleComponent implements UIHttpServer {
    /// Request-attribute key set by [UIServerRuntime#dispatchApiPath] (and any other dispatcher that wants per-route metrics) to override the default
    /// `path` tag derived by this server's request-timing hook from the URL. The value, if present, must be a [String]; the bounded set of legal values
    /// is the union of registered [ApiPathHandler#pathPrefix] values.
    static final String ROUTE_NAME_ATTRIBUTE = "metrics.routeName";
    private static final Logger logger = LogManager.getLogger(UIHttpServerImpl.class);

    /// Bounded request-handling thread pool. SSE streams run async — their writes are marshalled onto the
    /// UI executor, never held on a Jetty thread — so they do not occupy this pool per-stream; the max is
    /// sized for concurrent short requests across the user base, not for stream count.
    @VisibleForTesting
    static final int MAX_THREADS = 32;
    @VisibleForTesting
    static final int MIN_THREADS = 8;
    /// Accept backlog bound: excess inbound connections are refused by the OS rather than queued unbounded.
    private static final int ACCEPT_QUEUE_SIZE = 128;
    /// Request header size cap — app-side defence-in-depth alongside the Caddy edge header cap.
    private static final int REQUEST_HEADER_SIZE_BYTES = 16 * 1024;

    private final Set<ServletMount> servletMounts;
    private final MeterRegistry meterRegistry;
    private final QueuedThreadPool threadPool;
    private final Server server;
    private final ServerConnector connector;

    @Inject
    UIHttpServerImpl(@ListenPort int listenPort, Set<ServletMount> servletMounts, MeterRegistry meterRegistry) {
        this.servletMounts = checkNotNull(servletMounts, "servletMounts");
        this.meterRegistry = checkNotNull(meterRegistry, "meterRegistry");
        checkArgument(listenPort >= 0 && listenPort <= 65_535, "listenPort: %s", listenPort);
        threadPool = new QueuedThreadPool(MAX_THREADS, MIN_THREADS);
        threadPool.setName("ui-http");
        server = new Server(threadPool);
        var httpConfig = new HttpConfiguration();
        httpConfig.setFormEncodedMethods("POST");
        httpConfig.setRequestHeaderSize(REQUEST_HEADER_SIZE_BYTES);
        connector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
        connector.setPort(listenPort);
        connector.setAcceptQueueSize(ACCEPT_QUEUE_SIZE);
        server.addConnector(connector);
    }

    @Override
    public int listenPort() {
        return whenStartedAndNotLifecycling(connector::getLocalPort);
    }

    @Override
    protected void doStart() {
        registerThreadPoolMetrics();
        var contexts = new ContextHandlerCollection();
        for (ServletMount mount : servletMounts) {
            contexts.addHandler(mount.buildHandler());
        }
        server.setHandler(new TimingEventsHandler(contexts, meterRegistry, collectAllowedFirstSegments(contexts)));
        server.setErrorHandler(new JsonErrorHandler());
        asUnchecked(server::start);
    }

    /// Publishes the request-handling pool's saturation to Micrometer under the standard `jetty_threads_*`
    /// names (busy/idle vs configured max) so the shared thread ceiling is observable and alertable.
    private void registerThreadPoolMetrics() {
        Gauge.builder("jetty_threads_config_max", threadPool, QueuedThreadPool::getMaxThreads).register(meterRegistry);
        Gauge.builder("jetty_threads_config_min", threadPool, QueuedThreadPool::getMinThreads).register(meterRegistry);
        Gauge.builder("jetty_threads_current", threadPool, QueuedThreadPool::getThreads).register(meterRegistry);
        Gauge.builder("jetty_threads_idle", threadPool, QueuedThreadPool::getIdleThreads).register(meterRegistry);
        Gauge.builder("jetty_threads_busy", threadPool, p -> p.getThreads() - p.getIdleThreads()).register(meterRegistry);
        Gauge.builder("jetty_threads_jobs", threadPool, QueuedThreadPool::getQueueSize).register(meterRegistry);
    }

    /// Normalises each registered [ContextHandler]'s context path to its first URL segment (e.g. `/ui/api` → `/ui`, `/admin/api` → `/admin`, `/ui` → `/ui`).
    /// The resulting set is the bounded universe of legal `path` tag values for requests that do not carry a [#ROUTE_NAME_ATTRIBUTE] override; everything
    /// outside it collapses to `"unmatched"` in [TimingEventsHandler#resolvePath].
    private static ImmutableSet<String> collectAllowedFirstSegments(ContextHandlerCollection contexts) {
        var prefixes = ImmutableSet.<String>builder();
        for (Handler child : contexts.getHandlers()) {
            if (child instanceof ContextHandler ctx) {
                String contextPath = ctx.getContextPath();
                if (contextPath != null && !contextPath.isEmpty()) {
                    int secondSlash = contextPath.indexOf('/', 1);
                    prefixes.add(secondSlash == -1 ? contextPath : contextPath.substring(0, secondSlash));
                }
            }
        }
        return prefixes.build();
    }

    @Override
    protected void doStop() {
        Closeable.closeSafelyIfNotNull(logger, server::stop);
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface ListenPort {
    }

    private static final class TimingEventsHandler extends EventsHandler {
        private static final String TTFB_TIMER = "http_response_begin_seconds";
        private static final String TOTAL_TIMER = "http_request_seconds";
        private static final String UNMATCHED_PATH = "unmatched";

        private final MeterRegistry meterRegistry;
        private final ImmutableSet<String> allowedFirstSegments;
        private final ConcurrentMap<TimerKey, Timer> responseBeginTimers = new ConcurrentHashMap<>();
        private final ConcurrentMap<TimerKey, Timer> requestTimers = new ConcurrentHashMap<>();

        TimingEventsHandler(Handler delegate, MeterRegistry meterRegistry, ImmutableSet<String> allowedFirstSegments) {
            super(checkNotNull(delegate, "delegate"));
            this.meterRegistry = checkNotNull(meterRegistry, "meterRegistry");
            this.allowedFirstSegments = checkNotNull(allowedFirstSegments, "allowedFirstSegments");
        }

        @Override
        protected void onResponseBegin(Request request, int status, HttpFields headers) {
            record(TTFB_TIMER, responseBeginTimers, request, status);
        }

        @Override
        protected void onComplete(Request request, int status, HttpFields headers, Throwable failure) {
            record(TOTAL_TIMER, requestTimers, request, status);
        }

        private void record(String timerName, ConcurrentMap<TimerKey, Timer> cache, Request request, int status) {
            var key = new TimerKey(request.getMethod(), status, resolvePath(request));
            Timer timer = cache.computeIfAbsent(key, k -> meterRegistry.timer(timerName,
                                                                              "method", k.method(),
                                                                              "status", Integer.toString(k.status()),
                                                                              "path", k.path()));
            timer.record(NanoTime.since(request.getBeginNanoTime()), NANOSECONDS);
        }

        /// Derives a bounded `path` tag. Preference order:
        ///   1. the [#ROUTE_NAME_ATTRIBUTE] attribute, set by the dispatching app code (e.g. [UIServerImpl] for [ApiPathHandler] routes);
        ///   2. the first URL path segment of the request when that segment is one of the registered top-level segments (the first segment of each
        ///      [ServletMount] context path — e.g. a mount at `/ui/api` contributes `/ui`). [EventsHandler#onResponseBegin] fires before the wrapped chain
        ///      attaches the matched servlet context to the [Request], so the URL segment is the identifier available at this stage.
        ///   3. `"unmatched"` for any other request path (browser auto-requests like `/favicon.ico`, probes, typos) so per-file paths cannot blow up the
        ///      `path` tag's cardinality.
        ///   4. `/` for the root path or anything that does not parse to a segment.
        private String resolvePath(Request request) {
            if (request.getAttribute(ROUTE_NAME_ATTRIBUTE) instanceof String override && !override.isEmpty()) {
                return override;
            }
            String urlPath = request.getHttpURI().getPath();
            if (urlPath == null || urlPath.isEmpty() || "/".equals(urlPath)) {
                return "/";
            }
            int secondSlash = urlPath.indexOf('/', 1);
            String firstSegment = secondSlash == -1 ? urlPath : urlPath.substring(0, secondSlash);
            return allowedFirstSegments.contains(firstSegment) ? firstSegment : UNMATCHED_PATH;
        }

        private record TimerKey(String method, int status, String path) {
        }
    }
}
