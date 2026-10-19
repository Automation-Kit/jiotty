package net.yudichev.jiotty.user.ui;

import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.reflect.TypeToken;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.adminalerts.TestAdminAlertService;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SingleThreadedSchedulingExecutor;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.user.ui.io.Answer;
import net.yudichev.jiotty.user.ui.io.AsyncExchange;
import net.yudichev.jiotty.user.ui.io.Deadline;
import net.yudichev.jiotty.user.ui.io.ReceivedBody;
import net.yudichev.jiotty.user.ui.io.RequestBodies;
import net.yudichev.jiotty.user.ui.io.ResponseBody;
import net.yudichev.jiotty.user.ui.io.TextBody;
import net.yudichev.jiotty.user.ui.sse.SseAdmissionImpl;
import net.yudichev.jiotty.user.ui.sse.SseChannel;
import net.yudichev.jiotty.user.ui.sse.SseInternals;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.Math.toIntExact;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static net.yudichev.jiotty.common.lang.Closeable.closeIfNotNull;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static net.yudichev.jiotty.common.rest.ContentTypes.CONTENT_TYPE_TEXT;
import static net.yudichev.jiotty.common.rest.HttpStatuses.INTERNAL_SERVER_ERROR_500;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NO_CONTENT_204;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static net.yudichev.jiotty.common.rest.HttpStatuses.SERVICE_UNAVAILABLE_503;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The non-blocking answers, request bodies and event streams over a real Jetty server: which thread does the work, what reaches the client, and what
/// happens to a client that stops reading.
class ContainerIoJettyTest {
    /// Both ends address the same literal, so a request cannot reach another server in the JVM holding the same port on the other address family.
    private static final String LOOPBACK = "127.0.0.1";
    private static final String CONTEXT_PATH = "/io";
    private static final String ANSWER_SERVLET_PATH = "/answer";
    private static final String BODY_SERVLET_PATH = "/body";
    private static final String STREAM_SERVLET_PATH = "/stream";
    private static final String ENDING_SERVLET_PATH = "/ending";
    private static final String AFTER_END_SERVLET_PATH = "/after-end";
    private static final String UNANSWERED_SERVLET_PATH = "/unanswered";
    private static final String THROWING_SERVLET_PATH = "/throwing";
    private static final String ABORTING_SERVLET_PATH = "/aborting";
    /// Short, so the deadline case finishes quickly.
    private static final Duration SHORT_DEADLINE = Duration.ofMillis(200);
    private static final String DEADLINE_TEXT = "past the deadline";
    private static final Answer DEADLINE_ANSWER = Answer.of(SERVICE_UNAVAILABLE_503, new TextBody(DEADLINE_TEXT));
    /// Written by the [#AFTER_END_SERVLET_PATH] servlet when the ended exchange rejects a task and nothing was alerted, as counted on the serving thread,
    /// which is the thread that raises any alert.
    private static final String REJECTED_UNALERTED_LINE = "rejected, alerts: 0";
    /// A deadlock safety net for the cross-thread assertions.
    private static final Duration ASSERTION_TIMEOUT = Duration.ofSeconds(30);
    /// Well inside the production idle timeout, so a stream closed within it was dropped on overflow.
    private static final Duration OVERFLOW_DROP_TIMEOUT = UIHttpServerImpl.IDLE_TIMEOUT.dividedBy(3);
    /// Short, so the idle-timeout case finishes quickly.
    private static final Duration SHORT_IDLE_TIMEOUT = Duration.ofSeconds(1);
    private static final ObjectWriter EVENT_WRITER = Json.createWriterFor(new TypeToken<>() {});
    private static final ObjectReader PAYLOAD_READER = Json.createReaderFor(Payload.class);
    /// Large enough that a few of them fill the socket buffers of a client that never reads.
    private static final String LARGE_TEXT = "L".repeat(256 * 1024);

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ExecutorService applicationExecutor = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "application"));
    private final SingleThreadedSchedulingExecutor channelExecutor = new SingleThreadedSchedulingExecutor("sse-channel");
    private final CompletableFuture<SseChannel.SseStream> openedStream = new CompletableFuture<>();
    private final CompletableFuture<Void> streamClosure = new CompletableFuture<>();
    private final CompletableFuture<String> encodingThreadName = new CompletableFuture<>();
    private final CompletableFuture<AsyncExchange> endingExchange = new CompletableFuture<>();
    private final CompletableFuture<Void> abortReturned = new CompletableFuture<>();
    private final TestAdminAlertService alertService = new TestAdminAlertService();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private @Nullable UIHttpServerImpl server;
    private @Nullable SseChannel channel;
    private @Nullable Socket clientSocket;
    private SseAdmissionImpl admission;

    @BeforeEach
    void setUp() {
        admission = new SseAdmissionImpl(meterRegistry);
    }

    @AfterEach
    void tearDown() {
        closeIfNotNull(clientSocket);
        if (server != null) {
            server.stop();
        }
        closeIfNotNull(channel, channelExecutor);
        applicationExecutor.shutdownNow();
        closeIfNotNull(httpClient);
    }

    /// The application thread passes an [Answer] to [AsyncExchange#answer], and a container thread encodes and writes its body.
    @Test
    void anAnswerIsEncodedAndWrittenOnAContainerThread() {
        startServer(UIHttpServerImpl.IDLE_TIMEOUT);

        HttpResponse<String> response = send(HttpRequest.newBuilder(uriOf(ANSWER_SERVLET_PATH)).GET());

        assertThat(response.statusCode()).isEqualTo(OK_200);
        assertThat(response.body()).isEqualTo(LARGE_TEXT);
        assertThat(encodingThreadName).succeedsWithin(ASSERTION_TIMEOUT).asString().startsWith(UIHttpServerImpl.THREAD_POOL_NAME);
    }

    @Test
    void aRequestBodyIsReadAndParsedAsItArrives() {
        startServer(UIHttpServerImpl.IDLE_TIMEOUT);
        String text = "b".repeat(40 * 1024);

        HttpResponse<String> response = send(HttpRequest.newBuilder(uriOf(BODY_SERVLET_PATH))
                                                        .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(new Payload(text)))));

        assertThat(response.statusCode()).isEqualTo(OK_200);
        assertThat(response.body()).isEqualTo(String.valueOf(text.length()));
    }

    /// An exchange nothing answers is answered by its deadline, which runs on the server's own timer.
    @Test
    void anUnansweredExchangeIsAnsweredByItsDeadline() {
        startServer(UIHttpServerImpl.IDLE_TIMEOUT);

        HttpResponse<String> response = send(HttpRequest.newBuilder(uriOf(UNANSWERED_SERVLET_PATH)).GET());

        assertThat(response.statusCode()).isEqualTo(SERVICE_UNAVAILABLE_503);
        assertThat(response.body()).isEqualTo(DEADLINE_TEXT);
    }

    /// The container sends no response for a request that has gone async, so [BaseHttpServlet] answers a dispatch that throws then.
    @Test
    void aDispatchThrowingAfterItsRequestWentAsyncIsAnswered() {
        startServer(UIHttpServerImpl.IDLE_TIMEOUT);

        HttpResponse<String> response = send(HttpRequest.newBuilder(uriOf(THROWING_SERVLET_PATH)).GET());

        assertThat(response.statusCode()).isEqualTo(INTERNAL_SERVER_ERROR_500);
        assertThat(Json.parse(response.body()).path("error").asText()).isEqualTo(ErrorResponse.INTERNAL_ERROR);
    }

    /// A response that fails after part of it was sent reaches the client cut off, so the part is never taken for the whole.
    @Test
    void anAbortedResponseReachesTheClientCutOff() {
        startServer(UIHttpServerImpl.IDLE_TIMEOUT);

        assertThatThrownBy(() -> send(HttpRequest.newBuilder(uriOf(ABORTING_SERVLET_PATH)).GET()))
                .rootCause()
                .isInstanceOf(IOException.class)
                .as("cut off, rather than left hanging")
                .isNotInstanceOf(HttpTimeoutException.class);
        // The client sees the cut-off while the abort is still running on the application thread, which is where an alert would come from.
        assertThat(abortReturned).succeedsWithin(ASSERTION_TIMEOUT);
        assertThat(alertService.activeAlertsById()).isEmpty();
    }

    /// Answering or starting a task on an exchange Jetty has completed and recycled finds it claimed or ended, so nothing is written and nothing alerted.
    @Test
    void anExchangeJettyHasRecycledRefusesWorkWithoutAnAlert() {
        startServer(UIHttpServerImpl.IDLE_TIMEOUT);

        // Pipelined on one connection, so Jetty takes the second request only once it has completed the first and recycled its exchange.
        Socket socket = connectAndSend(requestTo(ENDING_SERVLET_PATH) + requestTo(AFTER_END_SERVLET_PATH));
        clientSocket = socket;
        var responses = new BufferedReader(new InputStreamReader(getAsUnchecked(socket::getInputStream), UTF_8));

        assertThat(CompletableFuture.supplyAsync(() -> readUntil(responses, REJECTED_UNALERTED_LINE), applicationExecutor)).succeedsWithin(ASSERTION_TIMEOUT);
    }

    /// A frame smaller than the container's write buffer is flushed to the client as soon as it is written.
    @Test
    void aSmallEventReachesTheClientWithoutAFurtherWrite() {
        startServer(UIHttpServerImpl.IDLE_TIMEOUT);
        BufferedReader events = openStreamAndRead();
        SseChannel.SseStream stream = openedStreamWhenOpen();

        stream.send("small", "tiny");

        assertThat(CompletableFuture.supplyAsync(() -> readUntil(events, "event: small"), applicationExecutor)).succeedsWithin(ASSERTION_TIMEOUT);
    }

    /// A client that stops reading leaves the channel executor free: its frames wait for it, written by container threads, and never hold that executor.
    @Test
    void aStalledReaderLeavesTheChannelExecutorFree() {
        startServer(UIHttpServerImpl.IDLE_TIMEOUT);
        openStreamAndNeverRead();
        SseChannel.SseStream stream = openedStreamWhenOpen();

        for (int i = 0; i < 8; i++) {
            stream.sendUnsuperseded("large", LARGE_TEXT);
        }

        assertThat(channelExecutor.submit(() -> "free")).succeedsWithin(ASSERTION_TIMEOUT).isEqualTo("free");
        assertThat(streamClosure).as("the stream is open: the idle timeout has not fired").isNotDone();
    }

    /// Falling too far behind ends the stream at once and frees its slot.
    @Test
    void aStalledReaderThatFallsTooFarBehindIsDroppedAtOnce() {
        startServer(UIHttpServerImpl.IDLE_TIMEOUT);
        openStreamAndNeverRead();
        SseChannel.SseStream stream = openedStreamWhenOpen();

        // Twice the bound, so the frames left pending past the batch the first drain takes overflow it.
        for (int i = 0; i < 2 * SseInternals.PENDING_FRAMES_CAPACITY; i++) {
            stream.sendUnsuperseded("large", LARGE_TEXT);
        }

        assertThat(streamClosure).succeedsWithin(OVERFLOW_DROP_TIMEOUT);
        assertThat(meterRegistry.counter(SseInternals.DROPPED_COUNTER).count()).isEqualTo(1);
        assertThat(meterRegistry.get(SseInternals.ACTIVE_CLIENTS_GAUGE).gauge().value()).isZero();
    }

    @Test
    void aStalledReaderIsDroppedByTheIdleTimeout() {
        startServer(SHORT_IDLE_TIMEOUT);
        openStreamAndNeverRead();
        SseChannel.SseStream stream = openedStreamWhenOpen();

        for (int i = 0; i < 8; i++) {
            stream.sendUnsuperseded("large", LARGE_TEXT);
        }

        assertThat(streamClosure).succeedsWithin(ASSERTION_TIMEOUT);
        assertThat(meterRegistry.counter(SseInternals.DROPPED_COUNTER).count()).as("counted as a slow reader").isEqualTo(1);
    }

    private void startServer(Duration idleTimeout) {
        channel = new SseChannel("test", channelExecutor, new ProgrammableClock(), EVENT_WRITER, alertService, admission, 5);
        channel.start();
        SseChannel startedChannel = channel;
        ServletMount mount = () -> {
            var contextHandler = new ServletContextHandler();
            contextHandler.setContextPath(CONTEXT_PATH);
            contextHandler.addServlet(new ServletHolder(new BaseHttpServlet() {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response) {
                    var exchange = AsyncExchange.start(alertService, request, response, Deadline.none());
                    applicationExecutor.execute(() -> exchange.answer(Answer.of(OK_200, new ThreadRecordingBody(encodingThreadName)),
                                                                      encodingThreadName::completeExceptionally));
                }
            }), ANSWER_SERVLET_PATH);
            contextHandler.addServlet(new ServletHolder(new BaseHttpServlet() {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response) {
                    // Never answered, so the deadline answers.
                    AsyncExchange.start(alertService, request, response, Deadline.after(SHORT_DEADLINE, DEADLINE_ANSWER, _ -> {}, _ -> {}));
                }
            }), UNANSWERED_SERVLET_PATH);
            contextHandler.addServlet(new ServletHolder(new BaseHttpServlet() {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response) {
                    AsyncExchange.start(alertService, request, response, Deadline.none());
                    throw new IllegalStateException("a handler failing after its request went async");
                }
            }), THROWING_SERVLET_PATH);
            contextHandler.addServlet(new ServletHolder(new BaseHttpServlet() {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response) {
                    AsyncExchange.Claim claim = AsyncExchange.start(alertService, request, response, Deadline.none()).claim().orElseThrow();
                    // Written and flushed from the application thread, so part of the body has been sent before the abort.
                    applicationExecutor.execute(() -> {
                        try {
                            claim.response().setContentType(CONTENT_TYPE_TEXT);
                            ServletOutputStream output = claim.response().getOutputStream();
                            output.write(LARGE_TEXT.getBytes(UTF_8));
                            output.flush();
                            claim.abort(new IOException("the body failed part way"));
                        } catch (IOException e) {
                            claim.abort(e);
                        } finally {
                            abortReturned.complete(null);
                        }
                    });
                }
            }), ABORTING_SERVLET_PATH);
            contextHandler.addServlet(new ServletHolder(new BaseHttpServlet() {
                @Override
                protected void doPost(HttpServletRequest request, HttpServletResponse response) {
                    var exchange = AsyncExchange.start(alertService, request, response, Deadline.none());
                    RequestBodies.<Payload>read(exchange, 64 * 1024, PAYLOAD_READER).thenAcceptAsync(received -> {
                        String answer = switch (received) {
                            case ReceivedBody.Parsed(Payload payload) -> String.valueOf(payload.text().length());
                            case ReceivedBody.Empty<Payload> _ -> "empty";
                            case ReceivedBody.TooLarge<Payload> _ -> "too large";
                            case ReceivedBody.Malformed<Payload> _ -> "malformed";
                        };
                        exchange.answer(Answer.of(OK_200, new TextBody(answer)), _ -> {});
                    }, applicationExecutor);
                }
            }), BODY_SERVLET_PATH);
            contextHandler.addServlet(new ServletHolder(new BaseHttpServlet() {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response) {
                    var exchange = AsyncExchange.start(alertService, request, response, Deadline.none());
                    endingExchange.complete(exchange);
                    exchange.answer(Answer.of(NO_CONTENT_204), _ -> {});
                }
            }), ENDING_SERVLET_PATH);
            contextHandler.addServlet(new ServletHolder(new BaseHttpServlet() {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
                    AsyncExchange endedExchange = checkNotNull(endingExchange.getNow(null), "the ending exchange was answered first");
                    endedExchange.answer(Answer.of(OK_200, new TextBody("late")), _ -> {});
                    boolean accepted = endedExchange.runOnContainerThread(() -> {});
                    response.setContentType(CONTENT_TYPE_TEXT);
                    response.getWriter().println((accepted ? "accepted" : "rejected") + ", alerts: " + alertService.activeAlertsById().size());
                }
            }), AFTER_END_SERVLET_PATH);
            contextHandler.addServlet(new ServletHolder(new BaseHttpServlet() {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response) {
                    startedChannel.startStream(request, response, () -> streamClosure.complete(null), openedStream::complete);
                }
            }), STREAM_SERVLET_PATH);
            return contextHandler;
        };
        server = new UIHttpServerImpl(0, Set.of(mount), meterRegistry, idleTimeout, Optional.of(LOOPBACK));
        server.start();
    }

    private SseChannel.SseStream openedStreamWhenOpen() {
        assertThat(openedStream).succeedsWithin(ASSERTION_TIMEOUT);
        return openedStream.getNow(null);
    }

    private BufferedReader openStreamAndRead() {
        Socket socket = connectAndRequestStream();
        clientSocket = socket;
        return new BufferedReader(new InputStreamReader(getAsUnchecked(socket::getInputStream), UTF_8));
    }

    /// Opens a stream with as small a receive buffer as the platform allows, and never reads from it.
    private void openStreamAndNeverRead() {
        clientSocket = connectAndRequestStream();
    }

    private Socket connectAndRequestStream() {
        return connectAndSend(requestTo(STREAM_SERVLET_PATH));
    }

    private Socket connectAndSend(String requests) {
        return getAsUnchecked(() -> {
            var socket = new Socket();
            socket.setReceiveBufferSize(1024);
            // Bounded for the reason `send` is.
            socket.setSoTimeout(toIntExact(ASSERTION_TIMEOUT.toMillis()));
            socket.connect(new InetSocketAddress(LOOPBACK, listenPort()));
            OutputStream output = socket.getOutputStream();
            output.write(requests.getBytes(US_ASCII));
            output.flush();
            return socket;
        });
    }

    private static String requestTo(String servletPath) {
        return "GET " + CONTEXT_PATH + servletPath + " HTTP/1.1\r\nHost: " + LOOPBACK + "\r\n\r\n";
    }

    private static String readUntil(BufferedReader reader, String line) {
        return getAsUnchecked(() -> {
            String receivedLine;
            while ((receivedLine = reader.readLine()) != null) {
                if (receivedLine.equals(line)) {
                    return receivedLine;
                }
            }
            throw new IOException("the stream ended before " + line);
        });
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        // Bounded, so a server that never answers fails the test rather than hanging the build.
        return getAsUnchecked(() -> httpClient.send(request.timeout(ASSERTION_TIMEOUT).build(), HttpResponse.BodyHandlers.ofString()));
    }

    private URI uriOf(String servletPath) {
        return URI.create("http://" + LOOPBACK + ':' + listenPort() + CONTEXT_PATH + servletPath);
    }

    private int listenPort() {
        assert server != null : "the server is started";
        return server.listenPort();
    }

    private record Payload(String text) {}

    /// Records the name of the thread that encodes it, then writes [#LARGE_TEXT].
    private record ThreadRecordingBody(CompletableFuture<String> encodingThreadName) implements ResponseBody {
        @Override
        public void applyHeadersTo(HttpServletResponse response) {
            response.setContentType(CONTENT_TYPE_TEXT);
        }

        @Override
        public void writeTo(OutputStream output) throws IOException {
            encodingThreadName.complete(Thread.currentThread().getName());
            output.write(LARGE_TEXT.getBytes(UTF_8));
        }
    }
}
