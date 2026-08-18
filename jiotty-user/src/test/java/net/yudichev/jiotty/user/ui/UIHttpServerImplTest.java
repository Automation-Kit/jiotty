package net.yudichev.jiotty.user.ui;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.user.ui.UIRequestAuthoriser.StreamInvalidationSubscription;
import net.yudichev.jiotty.user.ui.UIRequestAuthoriser.UIRequestContext;
import net.yudichev.jiotty.user.ui.UIServerRuntime.DispatchResult;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NOT_FOUND_404;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UIHttpServerImplTest {
    /// Both ends address the same literal: `localhost` resolves to two address families, so on an ephemeral port a request can reach whichever server in the
    /// JVM holds that port number on the other one.
    private static final String LOOPBACK = "127.0.0.1";
    /// Short enough to keep the reclaim tests to a couple of seconds, long enough that ordinary scheduling jitter cannot be mistaken for an idle connection.
    private static final Duration TEST_IDLE_TIMEOUT = Duration.ofSeconds(1);
    /// A deadlock safety net for the cross-thread assertions, not a poll interval — the outcomes below arrive within one idle timeout or not at all.
    private static final Duration ASSERTION_TIMEOUT = Duration.ofSeconds(30);

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    @Mock
    private UIRequestAuthoriser requestAuthoriser;
    @Mock
    private UIServerRuntime runtime;
    @Mock
    private StreamInvalidationSubscription streamInvalidationSubscription;
    private UIHttpServerImpl server;
    private HttpClient httpClient;
    private @Nullable Socket stalledSocket;

    @BeforeEach
    void setUp() {
        server = new UIHttpServerImpl(0, defaultMounts(), meterRegistry);
        server.start();
        httpClient = HttpClient.newBuilder()
                               .followRedirects(HttpClient.Redirect.NEVER)
                               .build();

        asUnchecked(() -> lenient().doAnswer(invocation -> {
            var request = (HttpServletRequest) invocation.getArgument(0);
            var response = (HttpServletResponse) invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            RequestContextFilter.setRequestContext(request, new UIRequestContext(runtime, streamInvalidationSubscription));
            chain.doFilter(request, response);
            return null;
        }).when(requestAuthoriser).authorise(any(), any(), any()));

        lenient().when(streamInvalidationSubscription.subscribe(any())).thenAnswer(_ -> Closeable.noop());
        lenient().when(runtime.dispatchApiPath(any(), any())).thenReturn(DispatchResult.NOT_FOUND);
    }

    @AfterEach
    void tearDown() {
        Closeable.closeIfNotNull(stalledSocket);
        if (server != null) {
            server.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 65_536})
    void invalidPort_throwsException(int port) {
        assertThatThrownBy(() -> new UIHttpServerImpl(port, Set.of(), meterRegistry))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listenPort_returnsAssignedPort() {
        assertThat(server.listenPort()).isGreaterThan(0);
    }

    @Test
    void start_registersBoundedThreadPoolMetrics() {
        assertThat(meterRegistry.get("jetty_threads_config_max").gauge().value()).isEqualTo(UIHttpServerImpl.MAX_THREADS);
        assertThat(meterRegistry.get("jetty_threads_config_min").gauge().value()).isEqualTo(UIHttpServerImpl.MIN_THREADS);
        // Poll the live gauges too, so their pool-reading functions are exercised, not just registered.
        assertThat(meterRegistry.get("jetty_threads_current").gauge().value()).isGreaterThanOrEqualTo(0.0);
        assertThat(meterRegistry.get("jetty_threads_idle").gauge().value()).isGreaterThanOrEqualTo(0.0);
        assertThat(meterRegistry.get("jetty_threads_busy").gauge().value()).isGreaterThanOrEqualTo(0.0);
        assertThat(meterRegistry.get("jetty_threads_jobs").gauge().value()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void staticResourceGet_servesFile() {
        HttpResponse<String> response = sendGet("/ui/style.css");

        assertThat(response.statusCode()).isEqualTo(OK_200);
        assertThat(response.body()).isNotEmpty();
        verifyNoInteractions(requestAuthoriser);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "DELETE"})
    void apiUnknownPath_noHandlerMatches_returns404(String method) {
        HttpResponse<String> response = sendRequest(method, "/ui/api/unknown");

        assertThat(response.statusCode()).isEqualTo(NOT_FOUND_404);
        assertThat(response.body()).isEqualTo("{\"error\":\"Unknown path\"}");
        verify(runtime).dispatchApiPath(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "DELETE"})
    void apiUnknownPath_handlerMatches_runtimeWritesResponse(String method) {
        asUnchecked(() -> when(runtime.dispatchApiPath(any(), any())).thenAnswer(invocation -> {
            HttpServletResponse resp = invocation.getArgument(1);
            resp.setStatus(OK_200);
            resp.setContentType("text/plain");
            resp.getWriter().print("handled-by-api-path-handler");
            return DispatchResult.HANDLED;
        }));

        HttpResponse<String> response = sendRequest(method, "/ui/api/analytics/some-report");

        assertThat(response.statusCode()).isEqualTo(OK_200);
        assertThat(response.body()).isEqualTo("handled-by-api-path-handler");
        verify(runtime).dispatchApiPath(any(), any());
    }

    @Test
    void dispatchedRequest_pathTagDerivedFromRouteNameAttribute() {
        asUnchecked(() -> when(runtime.dispatchApiPath(any(), any())).thenAnswer(invocation -> {
            HttpServletRequest req = invocation.getArgument(0);
            HttpServletResponse resp = invocation.getArgument(1);
            req.setAttribute(UIHttpServerImpl.ROUTE_NAME_ATTRIBUTE, "/api/analytics");
            resp.setStatus(OK_200);
            resp.setContentType("text/plain");
            resp.getWriter().print("ok");
            return DispatchResult.HANDLED;
        }));

        HttpResponse<String> response = sendGet("/ui/api/analytics/some-report");

        assertThat(response.statusCode()).as("the handler's own response reaches the client").isEqualTo(OK_200);
        assertThat(response.body()).isEqualTo("ok");
        assertThat(meterRegistry.find("http_response_begin_seconds")
                                .tag("path", "/api/analytics")
                                .timer())
                .as("TTFB timer should be tagged with the matched route name")
                .isNotNull();
    }

    @Test
    void staticResource_pathTagFallsBackToFirstUrlSegment() {
        sendGet("/ui/style.css");

        assertThat(meterRegistry.find("http_response_begin_seconds")
                                .tag("path", "/ui")
                                .timer())
                .as("TTFB timer for a static resource falls back to the first URL segment")
                .isNotNull();
    }

    @Test
    void unhandledRootPath_taggedAsUnmatched() {
        sendGet("/favicon.ico");

        assertThat(meterRegistry.find("http_response_begin_seconds")
                                .tag("path", "unmatched")
                                .timer())
                .as("TTFB timer for an unhandled root path collapses into the 'unmatched' bucket")
                .isNotNull();
        assertThat(meterRegistry.find("http_response_begin_seconds")
                                .tag("path", "/favicon.ico")
                                .timer())
                .as("the literal per-file path must NOT appear as a tag value")
                .isNull();
    }

    @Test
    void multiSegmentContextMount_firstSegmentBecomesAllowedTag() {
        restartServerWith(createTextMount("/admin/api/alerts", "/x", "alerts-x"));

        sendGet("/admin/api/alerts/x");

        assertThat(meterRegistry.find("http_response_begin_seconds")
                                .tag("path", "/admin")
                                .timer())
                .as("a mount at /admin/api/alerts contributes /admin to the allowed first-segment set")
                .isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/unknown", "/ui/options", "/totally/unknown/path"})
    void outOfMountPath_returnsJsonNotFound(String path) {
        HttpResponse<String> response = sendGet(path);

        assertThat(response.statusCode()).isEqualTo(NOT_FOUND_404);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("application/json"));
        assertThat(response.body()).isEqualTo("{\"error\":\"Not found\"}");
        verifyNoInteractions(requestAuthoriser);
    }

    @Test
    void servletMount_handlesRequestAtMountedPath() {
        restartServerWith(createTextMount("/mounted", "/hello", "mounted-response"));

        HttpResponse<String> response = sendGet("/mounted/hello");

        assertThat(response.statusCode()).isEqualTo(OK_200);
        assertThat(response.body()).isEqualTo("mounted-response");
        verifyNoInteractions(requestAuthoriser);
    }

    @Test
    void multipleServletMounts_eachServesItsOwnContext() {
        restartServerWith(createTextMount("/first", "/hello", "first-response"), createTextMount("/second", "/hello", "second-response"));

        HttpResponse<String> firstResponse = sendGet("/first/hello");
        HttpResponse<String> secondResponse = sendGet("/second/hello");

        assertThat(firstResponse.statusCode()).isEqualTo(OK_200);
        assertThat(firstResponse.body()).isEqualTo("first-response");
        assertThat(secondResponse.statusCode()).isEqualTo(OK_200);
        assertThat(secondResponse.body()).isEqualTo("second-response");
        verifyNoInteractions(requestAuthoriser);
    }

    @Test
    void overlappingContexts_routeByLongestContextPath() {
        // Verifies that /ui/api/* is routed to the API mount even though /ui is a shorter-prefix sibling.
        HttpResponse<String> staticResponse = sendGet("/ui/style.css");
        HttpResponse<String> apiResponse = sendGet("/ui/api/displayables/unknown");

        assertThat(staticResponse.statusCode()).isEqualTo(OK_200);
        assertThat(staticResponse.body()).isNotEmpty();
        // /ui/api/* reaches the runtime's dispatchApiPath even when the path doesn't match any handler.
        verify(runtime).dispatchApiPath(any(), any());
        assertThat(apiResponse.statusCode()).isEqualTo(NOT_FOUND_404);
    }

    @Test
    void readerThatStopsReading_hasItsResponseAbortedAfterTheIdleTimeout() {
        var writeOutcome = new CompletableFuture<Throwable>();
        restartServerWith(TEST_IDLE_TIMEOUT, createStallingMount(writeOutcome));

        // Send the request, then never read a byte of the response: once the socket buffers fill, the server's write blocks with no way to make progress.
        // This is the shape a client that walks away mid-download presents, holding a request thread — and, on the export route, a recording connection —
        // until the connector reclaims it.
        sendAndNeverRead("GET /stall/big HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");

        assertThat(writeOutcome).as("the stalled response must be aborted rather than left holding the request")
                                .succeedsWithin(ASSERTION_TIMEOUT)
                                .isInstanceOf(IOException.class);
    }

    private Set<ServletMount> defaultMounts() {
        return Set.of(new ApiServletMount(requestAuthoriser), new StaticResourceServletMount());
    }

    /// Replaces the fixture's server with one serving `extraMounts` alongside the defaults.
    private void restartServerWith(ServletMount... extraMounts) {
        restartServerWith(UIHttpServerImpl.IDLE_TIMEOUT, extraMounts);
    }

    /// As above, on `idleTimeout`, so a reclaim test need not wait out the production [UIHttpServerImpl#IDLE_TIMEOUT].
    private void restartServerWith(Duration idleTimeout, ServletMount... extraMounts) {
        server.stop();
        var mounts = new LinkedHashSet<>(defaultMounts());
        Collections.addAll(mounts, extraMounts);
        server = new UIHttpServerImpl(0, mounts, meterRegistry, idleTimeout);
        server.start();
    }

    /// Writes far more than any socket buffer can absorb, so the write blocks unless the reader drains it, and reports how that write ended.
    private static ServletMount createStallingMount(CompletableFuture<Throwable> writeOutcome) {
        return createServletMount("/stall", "/big", (_, resp) -> {
            resp.setStatus(OK_200);
            resp.setContentType("application/octet-stream");
            var chunk = new byte[64 * 1024];
            try {
                ServletOutputStream out = resp.getOutputStream();
                for (int bytesWritten = 0; bytesWritten < 256 * 1024 * 1024; bytesWritten += chunk.length) {
                    out.write(chunk);
                }
                writeOutcome.complete(null);
            } catch (IOException e) {
                writeOutcome.complete(e);
            }
        });
    }

    private static ServletMount createTextMount(String contextPath, String servletPathSpec, String body) {
        return createServletMount(contextPath, servletPathSpec, (_, resp) -> {
            resp.setStatus(OK_200);
            resp.setContentType("text/plain");
            resp.getWriter().print(body);
        });
    }

    private static ServletMount createServletMount(String contextPath, String servletPathSpec, GetHandler handler) {
        return () -> {
            var contextHandler = new ServletContextHandler();
            contextHandler.setContextPath(contextPath);
            var servlet = new HttpServlet() {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                    handler.handle(req, resp);
                }
            };
            contextHandler.addServlet(new ServletHolder(servlet), servletPathSpec);
            return contextHandler;
        };
    }

    /// Opens a connection, writes `request`, and leaves it open without ever reading — the socket is closed by the fixture's teardown.
    private void sendAndNeverRead(String request) {
        asUnchecked(() -> {
            stalledSocket = new Socket();
            stalledSocket.connect(new InetSocketAddress(LOOPBACK, server.listenPort()));
            stalledSocket.getOutputStream().write(request.getBytes(US_ASCII));
            stalledSocket.getOutputStream().flush();
        });
    }

    private String baseUrl() {
        return "http://" + LOOPBACK + ':' + server.listenPort();
    }

    private HttpResponse<String> sendGet(String path) {
        return getAsUnchecked(() -> httpClient.send(
                HttpRequest.newBuilder().uri(URI.create(baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()));
    }

    private HttpResponse<String> sendRequest(String method, String path) {
        HttpRequest.BodyPublisher body = "POST".equals(method)
                                         ? HttpRequest.BodyPublishers.ofString("")
                                         : HttpRequest.BodyPublishers.noBody();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                                                 .uri(URI.create(baseUrl() + path))
                                                 .method(method, body);
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/x-www-form-urlencoded");
        }
        return getAsUnchecked(() -> httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString()));
    }

    private interface GetHandler {
        void handle(HttpServletRequest request, HttpServletResponse response) throws IOException;
    }
}
