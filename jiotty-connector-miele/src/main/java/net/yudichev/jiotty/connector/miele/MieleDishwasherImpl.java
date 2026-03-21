package net.yudichev.jiotty.connector.miele;

import com.google.common.reflect.TypeToken;
import com.google.inject.BindingAnnotation;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.common.lang.Listeners;
import net.yudichev.jiotty.common.lang.backoff.BackOff;
import net.yudichev.jiotty.common.lang.backoff.ExponentialBackOff;
import net.yudichev.jiotty.common.rest.ContentTypes;
import net.yudichev.jiotty.common.security.OAuth2TokenManager;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.time.Duration.ZERO;
import static java.time.Duration.ofMillis;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.idempotent;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.rest.RestClients.call;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.shutdown;
import static net.yudichev.jiotty.connector.miele.MieleStreamConnected.STREAM_CONNECTED;
import static net.yudichev.jiotty.connector.miele.MieleStreamDisconnected.STREAM_DISCONNECTED;

/// <a href="https://www.miele.com/developer/">Guide</a>
final class MieleDishwasherImpl extends BaseLifecycleComponent implements MieleDishwasher {
    private static final Logger logger = LogManager.getLogger(MieleDishwasherImpl.class);

    private final String baseUrl;
    private final String deviceId;
    private final OAuth2TokenManager tokenManager;
    private final ExecutorFactory executorFactory;
    private final CurrentDateTimeProvider dateTimeProvider;
    private final RetryableOperationExecutor retryableOperationExecutor;
    private final Consumer<? super OkHttpClient.Builder> commandClientCustomiser;
    private final Consumer<? super OkHttpClient.Builder> streamingClientCustomiser;
    private final int getCallRetryCount;
    private OkHttpClient eventingClient;
    @Nullable
    private EventStream activeEventStream;
    private int eventStreamListenerRegistrationCount;
    private OkHttpClient commandClient;
    private volatile String accessToken;
    private SchedulingExecutor executor;
    private Closeable tokenSubscription;

    @Inject
    MieleDishwasherImpl(@DeviceId String deviceId,
                        @Dependency OAuth2TokenManager tokenManager,
                        ExecutorFactory executorFactory,
                        CurrentDateTimeProvider dateTimeProvider,
                        @Dependency RetryableOperationExecutor retryableOperationExecutor) {
        this(deviceId,
             tokenManager,
             executorFactory,
             dateTimeProvider,
             retryableOperationExecutor,
             "https://api.mcs3.miele.com/v1",
             builder -> {},
             builder -> {},
             3);
    }

    MieleDishwasherImpl(String deviceId,
                        OAuth2TokenManager tokenManager,
                        ExecutorFactory executorFactory,
                        CurrentDateTimeProvider dateTimeProvider,
                        RetryableOperationExecutor retryableOperationExecutor,
                        String baseUrl,
                        Consumer<? super OkHttpClient.Builder> commandClientCustomiser,
                        Consumer<? super OkHttpClient.Builder> streamingClientCustomiser, int getCallRetryCount) {
        this.deviceId = checkNotNull(deviceId);
        this.tokenManager = checkNotNull(tokenManager);
        this.executorFactory = checkNotNull(executorFactory);
        this.dateTimeProvider = checkNotNull(dateTimeProvider);
        this.retryableOperationExecutor = checkNotNull(retryableOperationExecutor);
        this.baseUrl = checkNotNull(baseUrl);
        this.commandClientCustomiser = checkNotNull(commandClientCustomiser);
        this.streamingClientCustomiser = checkNotNull(streamingClientCustomiser);
        this.getCallRetryCount = getCallRetryCount;
    }

    @Override
    protected void doStart() {
        executor = executorFactory.createSingleThreadedSchedulingExecutor("MieleDishwasher");
        eventingClient = newClient(builder -> {
            streamingClientCustomiser.accept(builder);
            builder.readTimeout(ZERO).callTimeout(ZERO).writeTimeout(ZERO);
        });
        commandClient = newClient(commandClientCustomiser);
        CompletableFuture<Void> firstToken = new CompletableFuture<>();
        tokenSubscription = tokenManager.subscribeToAccessToken(token -> {
            boolean firstTime = accessToken == null;
            accessToken = token;
            if (firstTime) {
                firstToken.complete(null);
            }
        });
        if (!firstToken.isDone()) {
            logger.info("Awaiting for the access token to be delivered...");
            asUnchecked(() -> firstToken.get(5, TimeUnit.MINUTES));
            logger.info("Access token obtained");
        }
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, activeEventStream);
        closeSafelyIfNotNull(logger, tokenSubscription, () -> shutdown(eventingClient), () -> shutdown(commandClient), executor);
    }

    @Override
    public Closeable subscribeToEvents(Consumer<? super MieleEvent> eventHandler) {
        return whenStartedAndNotLifecycling(() -> {
            if (activeEventStream == null) {
                logger.debug("[{}] Creating event stream", deviceId);
                activeEventStream = new EventStream();
            }
            Closeable listenerRegistration = activeEventStream.listeners.addListener(eventHandler);
            eventStreamListenerRegistrationCount++;
            logger.debug("[{}] Subscribed to events {}, registrations now: {}", deviceId, eventHandler, eventStreamListenerRegistrationCount);
            return idempotent(() -> whenStartedAndNotLifecycling(() -> {
                listenerRegistration.close();
                eventStreamListenerRegistrationCount--;
                logger.debug("[{}] Unsubscribed from events {}, registrations now: {}", deviceId, eventHandler, eventStreamListenerRegistrationCount);
                if (eventStreamListenerRegistrationCount == 0) {
                    logger.debug("[{}] Closing event stream", deviceId);
                    closeSafelyIfNotNull(logger, activeEventStream);
                    activeEventStream = null;
                }
            }));
        });
    }

    @Override
    public CompletableFuture<List<MieleProgram>> getPrograms() {
        return invokeGet("programs", new TypeToken<List<MieleProgram>>() {})
                // Miele sends rubbish with repeated empty programs with id=0
                .thenApply(programs -> programs.stream().filter(program -> program.id() != 0).collect(toImmutableList()));
    }

    @Override
    public CompletableFuture<MieleActions> getActions() {
        return invokeGet("actions", new TypeToken<>() {});
    }

    private <T> CompletableFuture<T> invokeGet(String endpoint, TypeToken<T> responseType) {
        return whenStartedAndNotLifecycling(() -> {
            logger.debug("[{}] invoking GET {}", deviceId, endpoint);
            var request = new Request.Builder()
                    .url(baseUrl + "/devices/" + deviceId + "/" + endpoint)
                    .header("Authorization", "Bearer " + accessToken)
                    .get()
                    .build();
            return call(commandClient.newCall(request), responseType, getCallRetryCount).whenComplete(logResult());
        });
    }

    @Override
    public CompletableFuture<Void> startProgram(int programId) {
        return invokePut("programs", builder -> builder.setProgramId(programId));
    }

    @Override
    public CompletableFuture<Void> powerOn() {
        return getActions().thenCompose(actions -> actions.powerOnAvailable()
                                                   ? invokePut("actions", builder -> builder.setPowerOn(true))
                                                   : CompletableFuture.completedFuture(null));
    }

    @Override
    public CompletableFuture<Void> powerOff() {
        return getActions().thenCompose(actions -> actions.powerOffAvailable()
                                                   ? invokePut("actions", builder -> builder.setPowerOff(true))
                                                   : CompletableFuture.completedFuture(null));
    }

    private CompletableFuture<Void> invokePut(String endpoint, Consumer<MieleCommand.Builder> commandBuilder) {
        return whenStartedAndNotLifecycling(() -> {
            var builder = MieleCommand.builder();
            commandBuilder.accept(builder);
            var command = builder.build();
            logger.debug("[{}] invoking PUT {}: {}", deviceId, endpoint, command);
            var request = new Request.Builder()
                    .url(baseUrl + "/devices/" + deviceId + "/" + endpoint)
                    .header("Authorization", "Bearer " + accessToken)
                    .put(RequestBody.create(Json.stringify(command),
                                            MediaType.get(ContentTypes.CONTENT_TYPE_JSON)))
                    .addHeader(CONTENT_TYPE, ContentTypes.CONTENT_TYPE_JSON)
                    .build();

            return retryableOperationExecutor.withBackOffAndRetry(command.toString(), () -> call(commandClient.newCall(request), Void.class, 1))
                                             .whenComplete(logResult());
        });
    }

    private BiConsumer<Object, ? super Throwable> logResult() {
        return (response, throwable) -> logger.debug("[{}] Result: {}", deviceId, throwable == null ? response : humanReadableMessage(throwable));
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface DeviceId {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }

    private class EventStream extends BaseIdempotentCloseable {
        private static final AtomicInteger streamIdGenerator = new AtomicInteger();
        private static final Duration PING_AGE_BEFORE_RECONNECT = Duration.ofSeconds(20 * 6);
        private static final long MAX_RECONNECT_TIME_BEFORE_GIVING_UP = TimeUnit.DAYS.toMillis(7);
        public final Listeners<MieleEvent> listeners = new Listeners<>();
        private final Request request;
        private final BackOff reconnectBackoff;
        private final AtomicReference<Closeable> pingMonitor = new AtomicReference<>();
        @Nullable
        private Call call;
        private boolean connected;
        private volatile int streamId;
        private Instant lastPingTime = dateTimeProvider.currentInstant();

        protected EventStream() {
            request = new Request.Builder()
                    .url(baseUrl + "/devices/" + deviceId + "/events")
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "text/event-stream")
                    .build();
            reconnectBackoff = new ExponentialBackOff.Builder().setInitialIntervalMillis(10)
                                                               .setMaxIntervalMillis(10_000)
                                                               .setMaxElapsedTimeMillis(MAX_RECONNECT_TIME_BEFORE_GIVING_UP)
                                                               .setNanoClock(dateTimeProvider)
                                                               .build();
            executor.execute(this::connect);
        }

        /// @implNote executor thread
        private void connect() {
            streamId = streamIdGenerator.incrementAndGet();
            var pingCheckInterval = Duration.ofSeconds(20);
            logger.info("[{}][{}] connecting to SSE stream and starting ping check every {}", deviceId, streamId, pingCheckInterval);
            call = eventingClient.newCall(request);
            logger.debug("[{}][{}] new call is {}", deviceId, streamId, call);
            closeSafelyIfNotNull(logger, pingMonitor.getAndSet(executor.scheduleAtFixedRate(pingCheckInterval, this::checkPings)));
            call.enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    logger.debug("[{}][{}] call {} failed", deviceId, streamId, call, e);
                    executor.execute(() -> {
                        var activeCall = EventStream.this.call;
                        if (call == activeCall) {
                            handleFailure(humanReadableMessage(e));
                        } else {
                            logger.debug("""
                                         [{}] got failure callback for call {} while active call is {} and active streamId is {},
                                          likely a previous call failure -> ignored""",
                                         deviceId, call, activeCall, streamId);
                        }
                    });
                }

                @Override
                public void onResponse(Call call, Response response) {
                    // ! HTTP thread !
                    logger.debug("[{}][{}] call {} succeeded, response code {}", deviceId, streamId, call, response.code());
                    try (ResponseBody responseBody = response.body()) {
                        if (!response.isSuccessful()) {
                            executor.execute(() -> handleFailure(
                                    "failed to open SSE stream: response code " + response.code() + ", body " + getResponseBodyStr(responseBody)));
                            return;
                        }

                        if (responseBody == null) {
                            executor.execute(() -> handleFailure("failed to open SSE stream: response is empty"));
                            return;
                        }

                        // reset lastPingTime: treat successful re-connection as ping, so that the connection is given another PING_AGE_BEFORE_RECONNECT
                        executor.execute(() -> lastPingTime = dateTimeProvider.currentInstant());
                        logger.info("[{}][{}] successfully connected to SSE stream ({}), starting read loop",
                                    deviceId, streamId, response.code());
                        readLoop(responseBody);
                    }
                }
            });
        }

        private static String getResponseBodyStr(ResponseBody responseBody) {
            String responseBodyStr;
            try {
                responseBodyStr = responseBody == null ? "" : "body: " + responseBody.string();
            } catch (IOException | RuntimeException e) {
                responseBodyStr = "failed getting response body: " + humanReadableMessage(e);
            }
            return responseBodyStr;
        }

        private void readLoop(ResponseBody responseBody) {
            // Read the SSE events line by line
            // The SSE stream remains open indefinitely, so read it continuously
            String line;
            try {
                @Nullable String eventType = null;
                while ((line = responseBody.source().readUtf8Line()) != null) {
                    executor.execute(() -> {
                        reconnectBackoff.reset();
                        if (!connected) {
                            connected = true;
                            handleSafely(STREAM_CONNECTED);
                        }
                    });
                    logger.debug("[{}][{}] event: {}", deviceId, streamId, line);
                    var eventPrefix = "event: ";
                    var dataPrefix = "data: ";
                    if (line.startsWith(eventPrefix)) {
                        eventType = line.substring(eventPrefix.length());
                    } else if (line.startsWith(dataPrefix)) {
                        String eventData = line.substring(dataPrefix.length());
                        if (eventType == null) {
                            logger.info("[{}][{}] unexpected event data, not preceded with event type: {}", deviceId, streamId, eventData);
                        } else {
                            handleEvent(eventType, eventData);
                            eventType = null;
                        }
                    }
                }
            } catch (IOException e) {
                executor.execute(() -> handleReadLoopFailure(e));
            }
        }

        /// @implSpec executor thread
        private void handleReadLoopFailure(IOException e) {
            if (call == null) {
                logger.debug("[{}][{}] read loop failed while call or stream is closed : this is expected", deviceId, streamId, e);
            } else {
                logger.info("[{}][{}] read loop failed unexpectedly on call {}, will re-connect", deviceId, streamId, call, e);
                handleFailure(humanReadableMessage(e));
            }
        }

        /// @implSpec executor thread
        private void handleFailure(String failureReason) {
            if (connected) {
                connected = false;
                handleSafely(STREAM_DISCONNECTED);
            }
            var nextBackOffMillis = reconnectBackoff.nextBackOffMillis();
            if (nextBackOffMillis == BackOff.STOP) {
                logger.error("[{}][{}] stream failure: {}, been happening for {}, will not re-connect",
                             deviceId, streamId, failureReason, MAX_RECONNECT_TIME_BEFORE_GIVING_UP);
                closeStream();
            } else {
                logger.info("[{}][{}] stream failure: {}, will close and re-connect in {}ms", deviceId, streamId, failureReason, nextBackOffMillis);
                closeStream();
                executor.schedule(ofMillis(nextBackOffMillis), this::connect);
            }
        }

        private void handleEvent(String eventType, String data) {
            executor.execute(() -> {
                try {
                    switch (eventType) {
                        case "device" -> handle(Json.parse(data, MieleDevice.class));
                        case "actions" -> handle(Json.parse(data, new TypeToken<Map<String, MieleActions>>() {}).get(deviceId));
                        case "ping" -> handlePing();
                        default -> logger.debug("[{}][{}] ignoring unknown event type {}", deviceId, streamId, eventType);
                    }
                } catch (RuntimeException e) {
                    logger.error("[{}][{}] event handler failed", deviceId, streamId, e);
                }
            });
        }

        @Override
        protected final void doClose() {
            executor.execute(this::closeStream);
        }

        /// @implSpec executor thread
        private void closeStream() {
            Call callToCancel = null;
            if (call != null) {
                callToCancel = call;
                call = null;
            }
            closeSafelyIfNotNull(logger, pingMonitor.getAndSet(null));
            streamId = -1;
            if (callToCancel != null) {
                logger.info("[{}][{}] closing SSE stream", deviceId, streamId);
                // this will fail the read loop
                callToCancel.cancel();
                logger.info("[{}][{}] SSE stream closed", deviceId, streamId);
            }
        }

        /// @implSpec executor thread
        private void checkPings() {
            var pingAge = Duration.between(lastPingTime, dateTimeProvider.currentInstant());
            logger.debug("[{}][{}] lastPingTime: {}, pingAge: {}", deviceId, streamId, lastPingTime, pingAge);
            if (pingAge.compareTo(PING_AGE_BEFORE_RECONNECT) > 0) {
                handleFailure("re-connected or last ping received " + pingAge + " ago (>" + PING_AGE_BEFORE_RECONNECT + ")");
            }
        }

        private void handlePing() {
            lastPingTime = dateTimeProvider.currentInstant();
        }

        private void handle(MieleEvent event) {
            listeners.notify(event);
        }

        /// @implSpec executor thread
        private void handleSafely(MieleEvent event) {
            try {
                listeners.notify(event);
            } catch (RuntimeException e) {
                logger.warn("[{}][{}] event handler failed", deviceId, streamId, e);
            }
        }
    }
}
