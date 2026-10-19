package net.yudichev.jiotty.user.ui.sse;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.google.inject.BindingAnnotation;
import com.google.inject.assistedinject.Assisted;
import jakarta.inject.Inject;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.adminalerts.AdminAlertService;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.user.ui.ErrorResponse;
import net.yudichev.jiotty.user.ui.io.Answer;
import net.yudichev.jiotty.user.ui.io.AsyncExchange;
import net.yudichev.jiotty.user.ui.io.ContainerOutput;
import net.yudichev.jiotty.user.ui.io.Deadline;
import net.yudichev.jiotty.user.ui.io.EndReason;
import net.yudichev.jiotty.user.ui.io.OutputSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.net.HttpHeaders.CACHE_CONTROL;
import static com.google.common.net.HttpHeaders.CONNECTION;
import static com.google.common.net.HttpHeaders.RETRY_AFTER;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.adminalerts.AdminAlertSeverity.WARNING;
import static net.yudichev.jiotty.common.lang.AtomicIntegers.tryIncrementBelow;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.idempotent;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessageFormattable;
import static net.yudichev.jiotty.common.rest.ContentTypes.CHARSET_UTF_8;
import static net.yudichev.jiotty.common.rest.ContentTypes.CONTENT_TYPE_EVENT_STREAM;
import static net.yudichev.jiotty.common.rest.HttpStatuses.SERVICE_UNAVAILABLE_503;
import static net.yudichev.jiotty.common.security.LogRedaction.redact;

/// The server-sent-events transport shared by every SSE feature: one channel, a client per request via [#open], writes through [SseStream] or
/// [#broadcastSink()].
/// Each client's frames are written by a [ContainerOutput] on container threads, so the channel executor never waits on a slow reader.
// FieldAccessNotGuarded: the guard is the single-threaded executor rather than a lock, so IntelliJ cannot see that every access runs on it.
@SuppressWarnings("FieldAccessNotGuarded")
public final class SseChannel extends BaseIdempotentCloseable {
    /// The `maxClients` of a channel that accepts any number of concurrent streams.
    public static final int UNBOUNDED = Integer.MAX_VALUE;
    /// The most concurrent streams one user's channel accepts: one per device the user is signed in on.
    public static final int MAX_CLIENTS_PER_USER = 5;
    /// The error code of a 503 refusing a stream because the user's channel is full.
    @VisibleForTesting
    static final String CHANNEL_FULL_ERROR = "USER_AT_CAPACITY";
    /// How often every stream is written to, which is how a stream to a client that went away is found to have ended.
    @VisibleForTesting
    static final Duration HEARTBEAT_PERIOD = Duration.ofSeconds(15);
    /// The `Retry-After` of a stream refused because its channel is full. Past two [heartbeats][#HEARTBEAT_PERIOD], because a client that went away holds
    /// its slot until the second heartbeat write after it left: the first write to it succeeds locally.
    @VisibleForTesting
    static final Duration CHANNEL_FULL_RETRY_AFTER = Duration.ofSeconds(35);
    @VisibleForTesting
    static final Duration CLOSED_RETRY_AFTER = Duration.ofSeconds(1);
    @VisibleForTesting
    static final Duration AT_CAPACITY_RETRY_AFTER = Duration.ofSeconds(30);
    /// The error code of a 503 refusing a stream because the channel is closed.
    @VisibleForTesting
    static final String CLOSED_ERROR = "UNAVAILABLE";
    /// The error code of a 503 refusing a stream because the stream budget its channel shares is spent.
    @VisibleForTesting
    static final String AT_CAPACITY_ERROR = "AT_CAPACITY";
    @VisibleForTesting
    static final String SERIALISATION_FAILED_ALERT_TITLE = "SSE frame serialisation failed";
    @VisibleForTesting
    static final String OPEN_FAILED_ALERT_TITLE = "SSE stream could not be opened";

    private static final Logger logger = LogManager.getLogger(SseChannel.class);

    /// The stream [#open] returns for one that failed to open and has been answered: what it is sent goes nowhere.
    private static final SseStream FAILED_STREAM = new SseStream() {
        @Override
        public void send(String eventName, Object data) {
        }

        @Override
        public void send(String eventName, String key, Object data) {
        }

        @Override
        public void sendUnsuperseded(String eventName, Object data) {
        }

        @Override
        public void close() {
        }
    };

    private final String name;
    private final SchedulingExecutor executor;
    private final CurrentDateTimeProvider currentDateTimeProvider;
    private final ObjectWriter jsonWriter;
    private final AdminAlertService alertService;
    private final Consumer<Throwable> answerEncodingFailureHandler;
    private final SseAdmission admission;
    private final int maxClients;

    @GuardedBy("executor")
    private final Set<SseClient> sseClients = new HashSet<>();
    /// Numbers clients on the servlet threads that open them, before any task for them runs on the channel executor.
    private final AtomicInteger clientIdSeqNumGenerator = new AtomicInteger();
    /// Admitted clients, incremented on the servlet thread opening a stream and decremented on whichever thread ends it or finds it cannot open; none of
    /// those threads may read [#sseClients].
    private final AtomicInteger clientCount = new AtomicInteger();
    private final SseSink broadcastSink = new BroadcastSink();

    private Closeable heartbeat = Closeable.noop();

    /// @param name         identifies this channel in log lines and in each client's id
    /// @param jsonWriter   serialises event payloads; auto-close is stripped from it here, so an event write cannot close the stream it writes into. It must
    ///                     be rooted at [Object], as [Json#createWriterFor(TypeToken)] builds one from a [TypeToken] of [Object], because this channel
    ///                     writes its own `hello` and `ping` frames through it as well as the feature's
    /// @param alertService escalates a frame this channel cannot serialise, which is a defect in the feature's frame types, a stream it cannot open, and an
    ///                     answer to a stream it cannot encode
    /// @param admission    the budget of streams this channel shares with others, beside its own `maxClients`
    /// @param maxClients   the most concurrent streams this channel accepts, or [#UNBOUNDED]
    @Inject
    public SseChannel(@Assisted String name,
                      @Assisted SchedulingExecutor executor,
                      CurrentDateTimeProvider currentDateTimeProvider,
                      @Assisted ObjectWriter jsonWriter,
                      @Dependency AdminAlertService alertService,
                      @Dependency SseAdmission admission,
                      @Assisted int maxClients) {
        this.name = checkNotNull(name, "name");
        this.executor = checkNotNull(executor, "executor");
        this.currentDateTimeProvider = checkNotNull(currentDateTimeProvider, "currentDateTimeProvider");
        this.jsonWriter = checkNotNull(jsonWriter, "jsonWriter").without(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
        this.alertService = checkNotNull(alertService, "alertService");
        answerEncodingFailureHandler = failure -> alertService.raise(WARNING, "SSE stream answer could not be encoded", logger, failure);
        this.admission = checkNotNull(admission, "admission");
        checkArgument(maxClients > 0, "maxClients must be positive, was %s", maxClients);
        this.maxClients = maxClients;
    }

    public void start() {
        heartbeat = executor.scheduleAtFixedRate(HEARTBEAT_PERIOD, this::sendHeartbeat);
    }

    @Override
    protected void doClose() {
        closeSafelyIfNotNull(logger, heartbeat);
        executor.execute("closeSseClients", () -> closeSafelyIfNotNull(logger, sseClients));
    }

    /// Returns the sink that sends every event it is given to every open stream.
    public SseSink broadcastSink() {
        return broadcastSink;
    }

    /// Safe to call from any thread.
    @VisibleForTesting
    int clientCount() {
        return clientCount.get();
    }

    /// Starts `request`'s exchange and opens a stream on it, answering a refused stream with a 503 carrying the refusal's code and a stream that fails to
    /// open with an error. Its `onStreamClosed` runs in both cases.
    ///
    /// @return the new stream, or a no-op handle when it was refused or failed to open
    public Closeable startStream(HttpServletRequest request,
                                 HttpServletResponse response,
                                 Runnable onStreamClosed,
                                 Consumer<? super SseStream> onOpen) {
        var exchange = AsyncExchange.start(alertService, request, response, Deadline.none());
        return switch (tryOpen(exchange, onStreamClosed, onOpen)) {
            case Opened(SseStream stream) -> stream;
            case Refused(Refusal refusal) -> {
                logger.debug("[SSE {}] stream refused: {}", name, refusal);
                exchange.answer(refusal.answer, answerEncodingFailureHandler);
                onStreamClosed.run();
                yield Closeable.noop();
            }
            case FailedToOpen _ -> Closeable.noop();
        };
    }

    /// Opens an SSE stream on an `exchange` the caller has started and not claimed.
    ///
    /// @param onStreamClosed run once when the stream ends, however it ends: on the channel executor; on the thread that ended the stream when that
    ///                       executor has shut down or rejected the task; or on the calling thread when the stream fails to open
    /// @param onOpen         invoked on the channel executor with the new stream, the same object this method returns, before any other task for it
    /// @return the new stream; one that ignores what it is sent when the stream failed to open, which has been answered here; or empty when the channel is at
    ///         capacity, its shared budget is spent, or it is closed, in which case nothing has been written to the response and the caller answers it
    public Optional<SseStream> open(AsyncExchange exchange, Runnable onStreamClosed, Consumer<? super SseStream> onOpen) {
        return switch (tryOpen(exchange, onStreamClosed, onOpen)) {
            case Opened(SseStream stream) -> Optional.of(stream);
            case Refused _ -> Optional.empty();
            case FailedToOpen _ -> Optional.of(FAILED_STREAM);
        };
    }

    private OpenOutcome tryOpen(AsyncExchange exchange, Runnable onStreamClosed, Consumer<? super SseStream> onOpen) {
        if (isClosed()) {
            return new Refused(Refusal.CLOSED);
        }
        // The two admissions are separate counters, so a stream refused by the shared budget holds its per-channel slot for a moment, and a concurrent open
        // of the same channel can be refused CHANNEL_FULL meanwhile, counted under user_at_capacity. That open would have met the spent budget a moment
        // later, so only the code of its 503 differs.
        if (!admitClient()) {
            admission.recordChannelFull();
            return new Refused(Refusal.CHANNEL_FULL);
        }
        if (!admission.tryAdmit()) {
            clientCount.decrementAndGet();
            return new Refused(Refusal.AT_CAPACITY);
        }
        // Claimed only once admitted, so a refusal is left for the caller to answer.
        Optional<AsyncExchange.Claim> heldClaim = exchange.claim();
        if (heldClaim.isEmpty()) {
            // Something answered the exchange first, so there is nothing left to stream to.
            releaseSlots();
            return new Refused(Refusal.CLOSED);
        }
        AsyncExchange.Claim claim = heldClaim.get();
        int clientIdSeqNum = clientIdSeqNumGenerator.incrementAndGet();
        SseClient client;
        try {
            ServletRequest request = claim.request();
            // The remote host is the client's IP, which is personal data; the sequence number already identifies the client, so the host is kept only as a
            // redacted hint.
            String clientId = name + '/' + clientIdSeqNum + '/' + redact(request.getRemoteHost()) + ':' + request.getRemotePort();
            HttpServletResponse response = claim.response();
            response.setCharacterEncoding(CHARSET_UTF_8);
            response.setContentType(CONTENT_TYPE_EVENT_STREAM);
            response.setHeader(CACHE_CONTROL, "no-cache");
            response.setHeader(CONNECTION, "keep-alive");
            response.setHeader("X-Accel-Buffering", "no");
            response.setHeader("X-Client-Id-Seq-Num", String.valueOf(clientIdSeqNum));
            response.flushBuffer();
            client = new SseClient(claim, clientId, idempotent(onStreamClosed::run));
        } catch (IOException e) {
            // The connection has gone before the stream carried anything.
            if (logger.isDebugEnabled()) {
                logger.debug("[SSE {}] could not open stream {}: the client has gone", name, clientIdSeqNum, e);
            }
            claim.complete();
            return failedToOpen(onStreamClosed);
        } catch (RuntimeException e) {
            if (exchange.isOpen()) {
                alertService.raise(WARNING, OPEN_FAILED_ALERT_TITLE, logger, e);
            } else if (logger.isDebugEnabled()) {
                // The exchange ended first, as when the client went away, which explains the failure.
                logger.debug("[SSE {}] could not open stream {}: the exchange has ended", name, clientIdSeqNum, e);
            }
            // The holder of the claim answers, and nobody else may: committed, the headers are on the wire and only completing is left.
            if (claim.response().isCommitted()) {
                claim.complete();
            } else {
                claim.answer(ErrorResponse.SERVER_FAULT, answerEncodingFailureHandler);
            }
            return failedToOpen(onStreamClosed);
        }
        logger.debug("[SSE {}] created", client.clientId);
        try {
            client.start(new HelloFrame(clientIdSeqNum, currentDateTimeProvider.currentInstant()));
        } catch (RuntimeException e) {
            // The container would not let the stream be written: end it, which gives back its slots and completes its exchange.
            logger.debug("[SSE {}] could not start writing", client.clientId, e);
            client.close();
            return new Opened(client);
        }
        try {
            executor.execute("openSseClient", () -> registerClient(client, onOpen));
        } catch (RuntimeException e) {
            // The registration task was rejected, so nothing downstream will deliver the initial image: end the stream, which gives back its slots. Its
            // removal goes to the same full queue, so onStreamClosed runs here; it runs once however many paths reach it.
            client.close();
            closeSafelyIfNotNull(logger, client.onStreamClosed);
            throw e;
        }
        return new Opened(client);
    }

    /// Claims a slot against [#maxClients], or reports that the channel is full. Compare-and-set so two simultaneous opens at the limit cannot both admit.
    private boolean admitClient() {
        if (tryIncrementBelow(clientCount, maxClients)) {
            return true;
        }
        if (logger.isDebugEnabled()) {
            logger.debug("[SSE {}] refusing client: at capacity of {}", name, maxClients);
        }
        return false;
    }

    private void releaseSlots() {
        clientCount.decrementAndGet();
        admission.release();
    }

    /// Gives back the slots of a stream admitted and never created, and runs its `onStreamClosed`: the stream has ended before it began.
    private OpenOutcome failedToOpen(Runnable onStreamClosed) {
        releaseSlots();
        // Logged rather than propagated: the callback is feature code.
        closeSafelyIfNotNull(logger, onStreamClosed::run);
        return FailedToOpen.INSTANCE;
    }

    /// Every exit leaves `client` either in the connected set with its initial image offered, or closed.
    private void registerClient(SseClient client, Consumer<? super SseStream> onOpen) {
        if (isClosed()) {
            // Closed between tryOpen() enqueuing this task and the executor draining it during teardown. The state this initial image reads has stopped too, so
            // skip delivery and end the stream — close()'s client sweep never saw this client, as it was not yet in sseClients when it ran.
            client.close();
            return;
        }
        if (client.isClosed()) {
            // The stream ended before it was registered. Its end queued a removal that finds nothing to remove, and that removal may also have been
            // rejected, so onStreamClosed runs here; it runs once however many of the two reach it.
            closeSafelyIfNotNull(logger, client.onStreamClosed);
            return;
        }
        sseClients.add(client);
        logger.debug("[SSE {}] delivering initial image", client.clientId);
        try {
            onOpen.accept(client);
        } catch (RuntimeException e) {
            // `onOpen` is feature code; a throw from it ends this stream, which releases the client's slots and completes its response.
            logger.debug("[SSE {}] failed to deliver the initial image", client.clientId, e);
            client.close();
        }
    }

    private void sendHeartbeat() {
        var ping = SseFrame.createSuperseding("ping", "ping", new PingFrame(currentDateTimeProvider.currentInstant()), jsonWriter);
        // Over a copy: removeAndNotify takes an ended client out of the set this walks.
        for (SseClient client : ImmutableList.copyOf(sseClients)) {
            if (client.isClosed()) {
                client.removeAndNotify();
            } else {
                client.offer(ping);
            }
        }
    }

    /// Somewhere events can be written: one open stream, or [SseChannel#broadcastSink()] for all of them. An unsent event is superseded by a later one
    /// carrying the same key, and its `data` must be immutable, because a container thread serialises it after the send has returned.
    public interface SseSink {
        /// Sends one event, superseding an unsent one of the same name. Safe to call from any thread.
        void send(String eventName, Object data);

        /// Sends one event, superseding an unsent one carrying the same `key`. Safe to call from any thread.
        void send(String eventName, String key, Object data);

        /// Sends one event that no later one supersedes. Safe to call from any thread.
        void sendUnsuperseded(String eventName, Object data);
    }

    /// One open stream, held for as long as the feature is streaming to that client. [Closeable#close()] ends it and runs its `onStreamClosed` callback.
    public interface SseStream extends SseSink, Closeable {
    }

    /// An [SseSink] that builds each event's [SseFrame] once and passes it to [#deliver].
    private interface FrameSink extends SseSink {
        /// Returns the writer the frames' payloads are serialised through.
        ObjectWriter frameWriter();

        void deliver(SseFrame frame);

        @Override
        default void send(String eventName, Object data) {
            deliver(SseFrame.createSuperseding(eventName, eventName, data, frameWriter()));
        }

        @Override
        default void send(String eventName, String key, Object data) {
            deliver(SseFrame.createSuperseding(eventName, key, data, frameWriter()));
        }

        @Override
        default void sendUnsuperseded(String eventName, Object data) {
            deliver(SseFrame.createUnsuperseded(eventName, data, frameWriter()));
        }
    }

    /// A [FrameSink] that marshals each frame onto the channel executor, which holds the set of clients.
    private final class BroadcastSink implements FrameSink {
        @Override
        public ObjectWriter frameWriter() {
            return jsonWriter;
        }

        @Override
        public void deliver(SseFrame frame) {
            executor.tryExecute("broadcastSseEvent", () -> {
                for (SseClient client : sseClients) {
                    client.offer(frame);
                }
            });
        }
    }

    /// One client's stream, closed once that stream has ended, however it ended.
    private final class SseClient extends BaseIdempotentCloseable implements SseStream, FrameSink, OutputSource {
        private final String clientId;
        private final Closeable onStreamClosed;
        private final PendingFrames pendingFrames = new PendingFrames();
        private final ContainerOutput output;
        /// Set by whichever offer first finds the client too far behind, on whichever thread made it.
        private final AtomicBoolean droppedForFallingBehind = new AtomicBoolean();

        /// @throws IOException if the response's output stream cannot be opened
        @SuppressWarnings("ThisEscapedInObjectConstruction")
        // the output only stores this client, and calls it no earlier than start()
        SseClient(AsyncExchange.Claim claim, String clientId, Closeable onStreamClosed) throws IOException {
            this.clientId = checkNotNull(clientId, "clientId");
            this.onStreamClosed = checkNotNull(onStreamClosed, "onStreamClosed");
            output = claim.createOutput(this, this::onEnded);
        }

        /// Offers the frame that opens the stream, then starts writing it. Runs on the thread opening the stream.
        void start(HelloFrame hello) {
            pendingFrames.offer(SseFrame.createOpening("hello", hello, jsonWriter));
            output.start();
        }

        /// Ends the stream; an end the stream reaches on its own closes this client as well, so this runs at most once either way.
        @Override
        protected void doClose() {
            output.close();
        }

        @Override
        public ObjectWriter frameWriter() {
            return jsonWriter;
        }

        @Override
        public void deliver(SseFrame frame) {
            offer(frame);
        }

        @Override
        public List<SseFrame> takeReady() {
            return pendingFrames.takeAll();
        }

        @Override
        public boolean hasReady() {
            return !pendingFrames.isEmpty();
        }

        @Override
        public boolean isFinished() {
            return false;
        }

        void offer(SseFrame frame) {
            // An expression, so an outcome added to the enum is a compile error here.
            boolean accepted = switch (pendingFrames.offer(frame)) {
                case ACCEPTED -> true;
                case OVERFLOWED -> false;
            };
            if (accepted) {
                output.sourceReady();
            } else if (droppedForFallingBehind.compareAndSet(false, true)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("[SSE {}] dropped: {} frames behind", clientId, PendingFrames.CAPACITY);
                }
                admission.recordSlowReaderDropped();
                output.close();
            }
        }

        /// Runs once, on the thread that ended the stream.
        private void onEnded(EndReason reason) {
            close();
            releaseSlots();
            switch (reason) {
                case EndReason.EncodingFailed(Exception cause) ->
                    // A frame the feature's writer cannot map is a defect in its frame types, not a client that went away, so it must not pass as one.
                        alertService.raise(WARNING, SERIALISATION_FAILED_ALERT_TITLE, logger, "serialising a frame", cause);
                case EndReason.Failed(Throwable cause) when cause instanceof TimeoutException -> {
                    // The container's idle timeout ends a write the client has stopped reading.
                    if (logger.isDebugEnabled()) {
                        logger.debug("[SSE {}] dropped: stopped reading ({})", clientId, humanReadableMessageFormattable(cause));
                    }
                    admission.recordSlowReaderDropped();
                }
                case EndReason.Failed(Throwable cause) -> logger.debug("[SSE {}] ended: {}", clientId, cause);
                case EndReason.Finished _, EndReason.Closed _ -> logger.debug("[SSE {}] closed", clientId);
            }
            // The callback is feature code, and runs on the channel executor so it is ordered with the feature's own work there.
            try {
                if (!executor.tryExecute("closeSseClient", this::removeAndNotify)) {
                    // The executor has shut down and runs nothing more, so this runs the feature's onStreamClosed directly; sseClients is read only on that
                    // executor, so it is left as it is.
                    closeSafelyIfNotNull(logger, onStreamClosed);
                }
            } catch (RejectedExecutionException e) {
                // A full queue: the heartbeat, which is scheduled, removes a registered client it finds ended, and registration notifies one that ended
                // before it ran.
                if (logger.isDebugEnabled()) {
                    logger.debug("[SSE {}] removal deferred: {}", clientId, humanReadableMessageFormattable(e));
                }
            }
        }

        /// Safe to run more than once: the heartbeat runs it for a client whose own removal task was rejected.
        void removeAndNotify() {
            sseClients.remove(this);
            // Logged rather than propagated: the callback is feature code, and the heartbeat runs this while iterating the connected set, where a throw
            // would skip every client after this one.
            closeSafelyIfNotNull(logger, onStreamClosed);
        }
    }

    private sealed interface OpenOutcome {}

    private record Opened(SseStream stream) implements OpenOutcome {
        private Opened {
            checkNotNull(stream, "stream");
        }
    }

    private record Refused(Refusal refusal) implements OpenOutcome {
        private Refused {
            checkNotNull(refusal, "refusal");
        }
    }

    /// The stream was admitted and could not be opened; the exchange has been answered, and the stream's `onStreamClosed` has run.
    private static final class FailedToOpen implements OpenOutcome {
        static final FailedToOpen INSTANCE = new FailedToOpen();
    }

    /// The reason a stream was refused, with the error code and `Retry-After` delay of its 503 answer.
    private enum Refusal {
        CLOSED(CLOSED_ERROR, CLOSED_RETRY_AFTER),
        CHANNEL_FULL(CHANNEL_FULL_ERROR, CHANNEL_FULL_RETRY_AFTER),
        AT_CAPACITY(AT_CAPACITY_ERROR, AT_CAPACITY_RETRY_AFTER);

        private final Answer answer;

        Refusal(String code, Duration retryAfter) {
            answer = ErrorResponse.createAnswer(SERVICE_UNAVAILABLE_503, code).withHeader(RETRY_AFTER, String.valueOf(retryAfter.toSeconds()));
        }
    }

    private record HelloFrame(int clientIdSeqNum, Instant serverTime) {}

    private record PingFrame(Instant serverTime) {}

    /// Qualifies the collaborators a channel takes from the feature that wires it: the [AdminAlertService] it escalates its own defects through, and the
    /// [SseAdmission] its streams count against.
    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    public @interface Dependency {
    }

    /// Creates channels from the parameters that differ between channels; the injector supplies the rest.
    public interface Factory {
        SseChannel create(String name, SchedulingExecutor executor, ObjectWriter jsonWriter, int maxClients);
    }
}
