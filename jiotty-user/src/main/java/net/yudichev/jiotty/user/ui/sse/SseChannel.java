package net.yudichev.jiotty.user.ui.sse;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.inject.BindingAnnotation;
import com.google.inject.assistedinject.Assisted;
import jakarta.inject.Inject;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.adminalerts.AdminAlertService;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.adminalerts.AdminAlertSeverity.WARNING;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.idempotent;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessageFormattable;
import static net.yudichev.jiotty.common.security.LogRedaction.redact;

/// The server-sent-events transport shared by every SSE feature: one channel, a client per request via [#open], writes through [SseStream] or [#broadcast].
/// Client bookkeeping is confined to `executor`, which the owning feature also uses for the state it streams.
public final class SseChannel extends BaseIdempotentCloseable {
    /// Passed as `maxClients` by a feature that does not cap concurrent streams.
    public static final int UNBOUNDED = Integer.MAX_VALUE;
    @VisibleForTesting
    static final Duration HEARTBEAT_PERIOD = Duration.ofSeconds(15);
    @VisibleForTesting
    static final String SERIALISATION_FAILED_ALERT_TITLE = "SSE frame serialisation failed";
    private static final Logger logger = LogManager.getLogger(SseChannel.class);
    private final String name;
    private final SchedulingExecutor executor;
    private final CurrentDateTimeProvider currentDateTimeProvider;
    private final ObjectWriter jsonWriter;
    private final AdminAlertService alertService;
    private final int maxClients;

    private final Set<SseClient> sseClients = new HashSet<>();
    /// Numbers clients on the servlet threads that open them, before any hop onto the channel executor.
    private final AtomicInteger clientIdSeqNumGenerator = new AtomicInteger();
    /// Counts admitted clients for the synchronous capacity check in [#open], which runs on a servlet thread and so cannot read [#sseClients].
    private final AtomicInteger clientCount = new AtomicInteger();

    private Closeable heartbeat = Closeable.noop();

    /// @param name         identifies this channel in log lines and in each client's id
    /// @param jsonWriter   serialises event payloads; auto-close is stripped from it here, so an event write cannot close the shared stream
    /// @param alertService escalates a frame this channel cannot serialise, which is a defect in the feature's frame types
    /// @param maxClients   the most concurrent streams this channel accepts, or [#UNBOUNDED]
    @Inject
    public SseChannel(@Assisted String name,
                      @Assisted SchedulingExecutor executor,
                      CurrentDateTimeProvider currentDateTimeProvider,
                      @Assisted ObjectWriter jsonWriter,
                      @Dependency AdminAlertService alertService,
                      @Assisted int maxClients) {
        this.name = checkNotNull(name, "name");
        this.executor = checkNotNull(executor, "executor");
        this.currentDateTimeProvider = checkNotNull(currentDateTimeProvider, "currentDateTimeProvider");
        this.jsonWriter = checkNotNull(jsonWriter, "jsonWriter").without(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
        this.alertService = checkNotNull(alertService, "alertService");
        checkArgument(maxClients > 0, "maxClients must be positive, was %s", maxClients);
        this.maxClients = maxClients;
    }

    public void start() {
        heartbeat = executor.scheduleAtFixedRate(HEARTBEAT_PERIOD, this::sendHeartbeat);
    }

    @Override
    protected void doClose() {
        closeSafelyIfNotNull(logger, heartbeat);
        executor.execute("closeSseClients", () -> {
            for (SseClient client : ImmutableList.copyOf(sseClients)) {
                closeAndRemoveClient(client);
            }
        });
    }

    /// Opens an SSE stream on an `asyncContext` the caller has already started, setting its timeout to zero.
    ///
    /// @param onStreamClosed run once when the stream ends, however it ends
    /// @param onOpen         receives the new stream on the channel executor, for the initial image; it is the same object this method returns, so a feature
    ///                         can attach per-stream state to it there without racing the handle's own publication
    /// @return the new stream, or empty when the channel is at capacity or already closed — the caller then writes its own rejection
    /// @throws IOException      if the event-stream headers could not be written
    /// @throws RuntimeException if the stream could not be opened. A response already committed has had its context completed here, so the caller reports the
    ///                         failure without writing to it; an uncommitted one is still the caller's to answer on
    public Optional<SseStream> open(AsyncContext asyncContext,
                                    HttpServletResponse response,
                                    Runnable onStreamClosed,
                                    Consumer<? super SseStream> onOpen) throws IOException {
        if (isClosed() || !admitClient()) {
            return Optional.empty();
        }
        SseClient client;
        int clientIdSeqNum = clientIdSeqNumGenerator.incrementAndGet();
        ServletRequest request = asyncContext.getRequest();
        // The remote host is the client's IP, which is personal data; the sequence number already identifies the client, so the host is kept only as a
        // redacted hint.
        String clientId = name + '/' + clientIdSeqNum + '/' + redact(request.getRemoteHost()) + ':' + request.getRemotePort();
        try {
            asyncContext.setTimeout(0);
            response.setCharacterEncoding("utf-8");
            response.setContentType("text/event-stream");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Connection", "keep-alive");
            response.setHeader("X-Accel-Buffering", "no");
            response.setHeader("X-Client-Id-Seq-Num", String.valueOf(clientIdSeqNum));
            response.flushBuffer();
            client = new SseClient(asyncContext, clientId, clientIdSeqNum, idempotent(onStreamClosed::run));
        } catch (IOException e) {
            // The client was admitted against the cap but never created, so release its slot. The context is left for the caller: an I/O failure this early
            // means the connection itself is gone, which its own error path already handles.
            clientCount.decrementAndGet();
            throw e;
        } catch (RuntimeException e) {
            clientCount.decrementAndGet();
            if (response.isCommitted()) {
                // The headers are already on the wire, so the caller has no response left to answer on — completing here is what makes this method's contract
                // true. Uncommitted, the caller still owns the exchange and writes its own failure, so completing would pull it out from under that write.
                completeQuietly(asyncContext, clientId);
            }
            throw e;
        }
        logger.debug("[SSE {}] created", clientId);
        try {
            executor.execute("openSseClient", () -> registerClient(asyncContext, client, onOpen));
        } catch (RuntimeException e) {
            // The registration task was refused, so nothing downstream will ever close this client: end it here, releasing its slot and completing its context.
            client.closeNow();
            throw e;
        }
        return Optional.of(client);
    }

    /// Every exit leaves `client` either fully wired up — in the connected set, preamble written, disconnect listener armed, initial image delivered — or
    /// closed and dropped from the set.
    private void registerClient(AsyncContext asyncContext, SseClient client, Consumer<? super SseStream> onOpen) {
        String clientId = client.clientId;
        if (isClosed()) {
            // Closed between open() enqueuing this task and the executor draining it during teardown. The state this initial image reads has stopped too,
            // so skip delivery and just close the freshly-created client to complete its stream and fire onStreamClosed — close()'s client drain never
            // saw this client, as it was not yet in sseClients when it ran.
            closeAndRemoveClient(client);
            return;
        }
        sseClients.add(client);
        client.init();
        if (client.closed) {
            // The hello frame could not be written, so the stream is already gone and its initial image has nowhere to go.
            closeAndRemoveClient(client);
            return;
        }
        try {
            asyncContext.addListener(new AsyncListener() {
                @Override
                public void onComplete(AsyncEvent event) {
                    logger.debug("[SSE {}] onComplete", clientId);
                    removeClient();
                }

                @Override
                public void onTimeout(AsyncEvent event) {
                    logger.debug("[SSE {}] onTimeout", clientId);
                    removeClient();
                }

                @Override
                public void onError(AsyncEvent event) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("[SSE {}] onError: {}", clientId, humanReadableMessageFormattable(event.getThrowable()));
                    }
                    removeClient();
                }

                @Override
                public void onStartAsync(AsyncEvent event) {
                    logger.debug("[SSE {}] onStartAsync", clientId);
                }

                private void removeClient() {
                    executor.tryExecute("closeSseClient", () -> closeAndRemoveClient(client));
                }
            });
        } catch (RuntimeException e) {
            logger.debug("[SSE {}] asyncContext.addListener failed", clientId, e);
            closeAndRemoveClient(client);
            return;
        }
        logger.debug("[SSE {}] delivering initial image", clientId);
        try {
            onOpen.accept(client);
        } catch (RuntimeException e) {
            // `onOpen` is feature code; a throw from it ends this stream, which releases the client's slot and completes its response.
            logger.debug("[SSE {}] failed to deliver the initial image", clientId, e);
            closeAndRemoveClient(client);
        }
    }

    /// Marshals onto the channel executor, so a feature broadcasting from its own thread cannot interleave with this channel's writes.
    public void broadcast(String eventName, Object data) {
        // Over a copy: a client whose write fails drops itself from the connected set as part of that failure.
        executor.tryExecute("broadcastSseEvent", () -> {
            for (SseClient client : ImmutableList.copyOf(sseClients)) {
                client.sendEvent(eventName, data);
            }
        });
    }

    /// Safe to call from any thread.
    @VisibleForTesting
    int clientCount() {
        return clientCount.get();
    }

    /// Claims a slot against [#maxClients], or reports that the channel is full. Compare-and-set so two simultaneous opens at the limit cannot both admit.
    private boolean admitClient() {
        int current;
        do {
            current = clientCount.get();
            if (current >= maxClients) {
                if (logger.isDebugEnabled()) {
                    logger.debug("[SSE {}] refusing client: at capacity of {}", name, maxClients);
                }
                return false;
            }
        } while (!clientCount.compareAndSet(current, current + 1));
        return true;
    }

    /// Removes in a `finally`, so a client whose teardown throws still leaves the connected set and stops taking a failed write on every broadcast.
    private void closeAndRemoveClient(SseClient client) {
        try {
            client.closeNow();
        } finally {
            sseClients.remove(client);
        }
    }

    private void sendHeartbeat() {
        var ping = new PingFrame(currentDateTimeProvider.currentInstant());
        // Over a copy: a client whose write fails drops itself from the connected set as part of that failure.
        for (SseClient client : ImmutableList.copyOf(sseClients)) {
            client.sendEvent("ping", ping);
        }
    }

    /// Completes `asyncContext`, reporting rather than propagating a failure to do so: every caller is already unwinding some other failure, and a context
    /// that refuses to complete is the container's business, not theirs.
    private static void completeQuietly(AsyncContext asyncContext, String clientId) {
        try {
            asyncContext.complete();
        } catch (RuntimeException e) {
            logger.debug("[SSE {}] asyncContext.complete failed", clientId, e);
        }
    }

    /// Somewhere events can be written: one open stream, or [SseChannel#broadcast] for all of them.
    public interface SseSink {
        /// Sends one event. Safe to call from any thread.
        void send(String eventName, Object data);
    }

    /// One open stream, held for as long as the feature is streaming to that client. [Closeable#close()] ends it and runs its `onStreamClosed` callback.
    public interface SseStream extends SseSink, Closeable {
    }

    // HardcodedLineSeparator: the SSE wire format specifies LF, so the platform separator would corrupt the frames on any host that does not use it.
    @SuppressWarnings("HardcodedLineSeparator")
    private final class SseClient implements SseStream {
        private final AsyncContext asyncContext;
        private final ServletOutputStream out;
        private final int clientIdSeqNum;
        private final String clientId;
        private final Closeable onStreamClosed;
        private boolean closed;

        SseClient(AsyncContext asyncContext, String clientId, int clientIdSeqNum, Closeable onStreamClosed) throws IOException {
            this.asyncContext = checkNotNull(asyncContext);
            this.clientId = checkNotNull(clientId);
            out = asyncContext.getResponse().getOutputStream();
            this.clientIdSeqNum = clientIdSeqNum;
            this.onStreamClosed = checkNotNull(onStreamClosed);
        }

        /// Marshals onto the channel executor, so a feature streaming from its own thread cannot interleave with this channel's writes.
        @Override
        public void send(String eventName, Object data) {
            executor.tryExecute("sendSseEvent", () -> sendEvent(eventName, data));
        }

        /// Ends the stream on the channel executor.
        @Override
        public void close() {
            executor.tryExecute("closeSseClient", () -> closeAndRemoveClient(this));
        }

        private void init() {
            try {
                logger.debug("[SSE {}] init", clientId);
                out.print("retry: 3000\n\n");
                sendEvent("hello", new HelloFrame(clientIdSeqNum, currentDateTimeProvider.currentInstant()));
                out.flush();
            } catch (IOException e) {
                logger.debug("[SSE {}] failed to write the stream preamble", clientId, e);
                closeNow();
            }
        }

        // OverlyBroadCatchBlock: writeValue declares StreamWriteException and DatabindException, but both are JsonProcessingException and both mean the
        // same thing here — the frame could not be serialised — so the parent is the precise catch, not a broad one.
        @SuppressWarnings("OverlyBroadCatchBlock")
        private void sendEvent(String eventName, Object data) {
            if (closed) {
                return;
            }
            try {
                logger.debug("[SSE {}] send event {}", clientId, eventName);
                out.print("event: ");
                out.print(eventName);
                out.print('\n');
                out.print("data: ");
                jsonWriter.writeValue(out, data);
                out.print("\n\n");
                out.flush();
            } catch (JsonProcessingException e) {
                // A frame the feature's writer cannot map is a defect in its frame types, not a client that went away, so it must not pass as one.
                alertService.raise(WARNING, SERIALISATION_FAILED_ALERT_TITLE, logger, "serialising the " + eventName + " frame", e);
                closeAndRemoveClient(this);
            } catch (IOException e) {
                logger.debug("[SSE {}] failed to send event {}", clientId, eventName, e);
                closeAndRemoveClient(this);
            }
        }

        /// Every close path arrives here, on the channel executor.
        private void closeNow() {
            if (closed) {
                return;
            }
            closed = true;
            clientCount.decrementAndGet();
            logger.debug("[SSE {}] closed", clientId);
            completeQuietly(asyncContext, clientId);
            // Logged rather than propagated: the callback is feature code, and this often runs while iterating the connected set, where a throw would skip
            // every client after this one.
            closeSafelyIfNotNull(logger, onStreamClosed);
        }
    }

    private record HelloFrame(int clientIdSeqNum, Instant serverTime) {}

    private record PingFrame(Instant serverTime) {}

    /// Qualifies the [AdminAlertService] a channel escalates its own defects through, so each feature wires the alerting it already uses.
    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    public @interface Dependency {
    }

    /// Builds channels for the feature that owns them: the feature supplies what only it knows, and its wiring supplies the rest.
    public interface Factory {
        SseChannel create(String name, SchedulingExecutor executor, ObjectWriter jsonWriter, int maxClients);
    }
}
