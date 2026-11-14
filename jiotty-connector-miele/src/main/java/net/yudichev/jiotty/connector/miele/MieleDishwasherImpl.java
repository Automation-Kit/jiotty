package net.yudichev.jiotty.connector.miele;

import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.common.lang.backoff.BackOff;
import net.yudichev.jiotty.common.lang.backoff.ExponentialBackOff;
import net.yudichev.jiotty.common.rest.ContentTypes;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.security.OAuth2TokenManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.net.HttpHeaders.ACCEPT;
import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.time.Duration.ZERO;
import static java.time.Duration.ofMillis;
import static java.util.concurrent.TimeUnit.MINUTES;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static net.yudichev.jiotty.common.rest.BearerTokens.BEARER_PREFIX;
import static net.yudichev.jiotty.common.rest.ContentTypes.CONTENT_TYPE_EVENT_STREAM;
import static net.yudichev.jiotty.common.rest.RestClients.call;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.shutdown;
import static net.yudichev.jiotty.common.rest.RestClients.shutdownNow;
import static net.yudichev.jiotty.connector.miele.MieleStreamConnected.STREAM_CONNECTED;
import static net.yudichev.jiotty.connector.miele.MieleStreamDisconnected.STREAM_DISCONNECTED;

/// A [MieleDishwasher] backed by the [Miele third-party API](https://www.miele.com/developer/).
public final class MieleDishwasherImpl extends BaseLifecycleComponent implements MieleDishwasher {
    private static final Logger logger = LogManager.getLogger(MieleDishwasherImpl.class);

    @VisibleForTesting
    static final Duration PING_CHECK_INTERVAL = Duration.ofSeconds(20);
    @VisibleForTesting
    static final Duration PING_AGE_BEFORE_RECONNECT = PING_CHECK_INTERVAL.multipliedBy(6);
    @VisibleForTesting
    static final Duration INITIAL_RECONNECT_DELAY = Duration.ofMillis(10);
    @VisibleForTesting
    static final Duration MAX_RECONNECT_DELAY = Duration.ofSeconds(10);
    @VisibleForTesting
    static final String EVENTS_ENDPOINT = "events";
    @VisibleForTesting
    static final String PROGRAMS_ENDPOINT = "programs";
    @VisibleForTesting
    static final String ACTIONS_ENDPOINT = "actions";
    @VisibleForTesting
    static final String EVENT_LINE_PREFIX = "event: ";
    @VisibleForTesting
    static final String DATA_LINE_PREFIX = "data: ";
    @VisibleForTesting
    static final String DEVICE_EVENT = "device";
    @VisibleForTesting
    static final String ACTIONS_EVENT = "actions";
    @VisibleForTesting
    static final String PING_EVENT = "ping";
    private static final TypeToken<Map<String, MieleActions>> ACTIONS_BY_DEVICE_ID = new TypeToken<>() {};
    private static final Duration MAX_RECONNECT_TIME_BEFORE_GIVING_UP = Duration.ofDays(7);
    private static final List<Protocol> EVENTING_PROTOCOLS = ImmutableList.of(Protocol.HTTP_1_1);
    private static final ObjectWriter COMMAND_WRITER = Json.createWriterFor(MieleCommand.class);
    /// Shared by the stream executors of every [MieleDishwasherImpl], so that a connection id in the logs names one connection of one dishwasher.
    private static final AtomicInteger connectionIdGenerator = new AtomicInteger();

    private final String baseUrl;
    private final String deviceId;
    private final HttpUrl eventsUrl;
    private final OAuth2TokenManager tokenManager;
    private final ExecutorFactory executorFactory;
    private final CurrentDateTimeProvider dateTimeProvider;
    private final RetryableOperationExecutor retryableOperationExecutor;
    private final Consumer<? super OkHttpClient.Builder> commandClientCustomiser;
    private final Consumer<? super OkHttpClient.Builder> streamingClientCustomiser;
    private final int getCallRetryCount;
    private OkHttpClient eventingClient;
    private OkHttpClient commandClient;
    private volatile String accessToken;
    private SchedulingExecutor executor;
    private Closeable tokenSubscription;
    private SharedEventStream sharedEventStream;

    @Inject
    public MieleDishwasherImpl(@DeviceId String deviceId,
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
             _ -> {},
             _ -> {},
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
        eventsUrl = HttpUrl.get(baseUrl + devicePath(deviceId, EVENTS_ENDPOINT));
    }

    @Override
    protected void doStart() {
        eventingClient = newClient(builder -> {
            streamingClientCustomiser.accept(builder);
            // HTTP/1.1 and a pool that keeps no idle connection, so that every connect, a re-subscription's included, opens a new TCP connection
            builder.readTimeout(ZERO)
                   .callTimeout(ZERO)
                   .writeTimeout(ZERO)
                   .protocols(EVENTING_PROTOCOLS)
                   .connectionPool(new ConnectionPool(0, 1, MINUTES));
        });
        commandClient = newClient(commandClientCustomiser);
        executor = executorFactory.createSingleThreadedSchedulingExecutor("MieleDishwasher");
        sharedEventStream = new SharedEventStream(executor, eventingClient);
        CompletableFuture<Void> firstToken = new CompletableFuture<>();
        tokenSubscription = tokenManager.subscribeToAccessTokenState(authState -> {
            switch (authState) {
                case AuthState.Success success -> {
                    accessToken = success.authInfo();
                    firstToken.complete(null);
                }
                // A transient failure — the state the manager holds until it resolves a token — can be followed by a token, so keep waiting for one. A
                // permanent failure means the user has to re-authorise, so fail the start at once.
                case AuthState.TransientFailure _ -> {}
                case AuthState.PermanentFailure failure ->
                        firstToken.completeExceptionally(new RuntimeException("Failed to obtain access token: " + failure.description()));
            }
        });
        if (!firstToken.isDone()) {
            logger.info("Awaiting for the access token to be delivered...");
        }
        // Awaited on every path, so that a permanent failure delivered synchronously on subscription propagates and fails the start.
        asUnchecked(() -> firstToken.get(5, MINUTES));
        logger.info("Access token obtained");
    }

    @Override
    protected void doStop() {
        // Closing the executor discards the event stream's scheduled reconnection and ping checks. shutdownNow then cancels the event stream's call, which
        // runs until cancelled, and the executor is closed by then, so the call's callbacks are dropped.
        closeSafelyIfNotNull(logger, tokenSubscription, executor, () -> shutdown(commandClient), () -> shutdownNow(eventingClient));
    }

    @Override
    public Closeable subscribeToEvents(Consumer<? super MieleEvent> eventHandler) {
        return whenStartedAndNotLifecycling(() -> {
            var subscription = new Subscription(sharedEventStream, eventHandler);
            sharedEventStream.join(subscription);
            return subscription;
        });
    }

    @Override
    public CompletableFuture<List<MieleProgram>> getPrograms() {
        return invokeGet(PROGRAMS_ENDPOINT, new TypeToken<List<MieleProgram>>() {})
                // Miele sends rubbish with repeated empty programs with id=0
                .thenApply(programs -> programs.stream().filter(program -> program.id() != 0).collect(toImmutableList()));
    }

    @Override
    public CompletableFuture<MieleActions> getActions() {
        return invokeGet(ACTIONS_ENDPOINT, new TypeToken<>() {});
    }

    private <T> CompletableFuture<T> invokeGet(String endpoint, TypeToken<T> responseType) {
        return whenStartedAndNotLifecycling(() -> {
            logger.debug("[{}] invoking GET {}", deviceId, endpoint);
            Request request = newDeviceRequest(deviceUrl(endpoint)).get().build();
            return call(commandClient.newCall(request), responseType, getCallRetryCount).whenComplete(logResult());
        });
    }

    @Override
    public CompletableFuture<Void> startProgram(int programId) {
        return invokePut(PROGRAMS_ENDPOINT, builder -> builder.setProgramId(programId));
    }

    @Override
    public CompletableFuture<Void> powerOn() {
        return getActions().thenCompose(actions -> actions.powerOnAvailable()
                                                   ? invokePut(ACTIONS_ENDPOINT, builder -> builder.setPowerOn(true))
                                                   : CompletableFuture.completedFuture(null));
    }

    @Override
    public CompletableFuture<Void> powerOff() {
        return getActions().thenCompose(actions -> actions.powerOffAvailable()
                                                   ? invokePut(ACTIONS_ENDPOINT, builder -> builder.setPowerOff(true))
                                                   : CompletableFuture.completedFuture(null));
    }

    private CompletableFuture<Void> invokePut(String endpoint, Consumer<MieleCommand.Builder> commandBuilder) {
        return whenStartedAndNotLifecycling(() -> {
            var builder = MieleCommand.builder();
            commandBuilder.accept(builder);
            var command = builder.build();
            logger.debug("[{}] invoking PUT {}: {}", deviceId, endpoint, command);
            Request request = newDeviceRequest(deviceUrl(endpoint)).put(RequestBody.create(getAsUnchecked(() -> COMMAND_WRITER.writeValueAsString(command)),
                                                                                           MediaType.get(ContentTypes.CONTENT_TYPE_JSON)))
                                                                   .addHeader(CONTENT_TYPE, ContentTypes.CONTENT_TYPE_JSON)
                                                                   .build();

            return retryableOperationExecutor.withBackOffAndRetry(command.toString(), () -> call(commandClient.newCall(request), Void.class, 1))
                                             .whenComplete(logResult());
        });
    }

    private HttpUrl deviceUrl(String endpoint) {
        return HttpUrl.get(baseUrl + devicePath(deviceId, endpoint));
    }

    /// @return the path, relative to the API's base URL, of `endpoint` of the device identified by `deviceId`
    @VisibleForTesting
    static String devicePath(String deviceId, String endpoint) {
        return "/devices/" + deviceId + '/' + endpoint;
    }

    private Request.Builder newDeviceRequest(HttpUrl url) {
        return new Request.Builder().url(url).header(AUTHORIZATION, BEARER_PREFIX + accessToken);
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

    /// The [EventStream] that the subscriptions taken during one start share. Its state is confined to [#streamExecutor], the [#executor] of that start, so
    /// a task of a stopped start's executor and a [Subscription] taken before a restart reach only the [SharedEventStream] of their own start.
    private final class SharedEventStream {
        private final SchedulingExecutor streamExecutor;
        private final OkHttpClient client;
        /// The stream the subscriptions share, `null` while no subscription has joined it.
        private @Nullable EventStream stream;

        SharedEventStream(SchedulingExecutor streamExecutor, OkHttpClient client) {
            this.streamExecutor = checkNotNull(streamExecutor);
            this.client = checkNotNull(client);
        }

        /// Adds `subscription` to [#stream], creating and starting the stream if `subscription` is the first.
        void join(Subscription subscription) {
            streamExecutor.execute("subscribe to events", () -> {
                if (stream == null) {
                    logger.debug("[{}] creating event stream", deviceId);
                    stream = new EventStream(streamExecutor, client);
                    stream.start();
                }
                stream.addSubscription(subscription);
            });
        }

        /// Removes `subscription` from [#stream], closing the stream if `subscription` is the last.
        ///
        /// @return `false` if [#streamExecutor] has shut down
        boolean tryLeave(Subscription subscription) {
            return streamExecutor.tryExecute("unsubscribe from events", () -> {
                assert stream != null : "the stream executor runs the task of join ahead of this one";
                if (stream.removeSubscription(subscription)) {
                    logger.debug("[{}] last subscriber left, closing event stream", deviceId);
                    closeSafelyIfNotNull(logger, stream);
                    stream = null;
                }
            });
        }
    }

    /// One subscriber's membership of the [SharedEventStream] of the start the subscription was taken in.
    private final class Subscription extends BaseIdempotentCloseable {
        private final SharedEventStream sharedStream;
        private final Consumer<? super MieleEvent> eventHandler;

        Subscription(SharedEventStream sharedStream, Consumer<? super MieleEvent> eventHandler) {
            this.sharedStream = checkNotNull(sharedStream);
            this.eventHandler = checkNotNull(eventHandler);
        }

        @Override
        protected void doClose() {
            if (!sharedStream.tryLeave(this)) {
                logger.debug("[{}] executor shut down together with the stream {} subscribed to", deviceId, eventHandler);
            }
        }

        /// Invokes [#eventHandler] with `event` unless [#close()] has been called, and logs an exception the handler throws. A delivery already under way
        /// when another thread calls [#close()] still completes.
        void deliver(MieleEvent event) {
            if (!isClosed()) {
                try {
                    eventHandler.accept(event);
                } catch (RuntimeException e) {
                    logger.info("[{}] subscriber {} failed handling {}", deviceId, eventHandler, event, e);
                }
            }
        }
    }

    /// The dishwasher's event stream, which the subscribers share. It holds one [Connection] at a time and opens a new one after each failure, following a
    /// back-off. A new subscription first receives the stream's [#connected] state, [#lastDevice] and [#lastActions].
    private final class EventStream extends BaseIdempotentCloseable {
        private final SchedulingExecutor streamExecutor;
        private final OkHttpClient client;
        private final List<Subscription> subscriptions = new ArrayList<>();
        private final BackOff reconnectBackoff = new ExponentialBackOff.Builder().setInitialIntervalMillis(INITIAL_RECONNECT_DELAY.toMillis())
                                                                                 .setMaxIntervalMillis(MAX_RECONNECT_DELAY.toMillis())
                                                                                 .setMaxElapsedTimeMillis(MAX_RECONNECT_TIME_BEFORE_GIVING_UP.toMillis())
                                                                                 .setNanoClock(dateTimeProvider)
                                                                                 .build();
        private @Nullable Connection connection;
        private @Nullable Closeable pendingReconnect;
        /// `null` until a connection first delivers a line or fails, then `true` when the latest of those was a delivered line.
        private @Nullable Boolean connected;
        private @Nullable MieleDevice lastDevice;
        private @Nullable MieleActions lastActions;

        EventStream(SchedulingExecutor streamExecutor, OkHttpClient client) {
            this.streamExecutor = checkNotNull(streamExecutor);
            this.client = checkNotNull(client);
        }

        @Override
        protected void doClose() {
            closeSafelyIfNotNull(logger, pendingReconnect);
            pendingReconnect = null;
            closeConnection();
        }

        void start() {
            streamExecutor.execute("connect event stream", this::connect);
        }

        void addSubscription(Subscription subscription) {
            subscriptions.add(subscription);
            if (connected != null) {
                subscription.deliver(connected ? STREAM_CONNECTED : STREAM_DISCONNECTED);
            }
            if (lastDevice != null) {
                subscription.deliver(lastDevice);
            }
            if (lastActions != null) {
                subscription.deliver(lastActions);
            }
        }

        /// @return whether `subscription` was the last subscription
        boolean removeSubscription(Subscription subscription) {
            subscriptions.remove(subscription);
            return subscriptions.isEmpty();
        }

        private void connect() {
            pendingReconnect = null;
            if (isClosed()) {
                logger.debug("[{}] stream closed, not connecting", deviceId);
                return;
            }
            assert connection == null : "a connect follows the closing of the previous connection";
            var newConnection = new Connection();
            connection = newConnection;
            newConnection.open();
        }

        private void onLineDelivered() {
            assert !Boolean.TRUE.equals(connected) : "a connection delivers its first line after the failure of the previous one";
            connected = true;
            publish(STREAM_CONNECTED);
        }

        private void onConnectionFailed(String failureReason) {
            if (!Boolean.FALSE.equals(connected)) {
                connected = false;
                publish(STREAM_DISCONNECTED);
            }
            closeConnection();
            long nextBackOffMillis = reconnectBackoff.nextBackOffMillis();
            if (nextBackOffMillis == BackOff.STOP) {
                logger.error("[{}] stream failure: {}, been happening for {}, will not re-connect",
                             deviceId, failureReason, MAX_RECONNECT_TIME_BEFORE_GIVING_UP);
            } else {
                logger.info("[{}] stream failure: {}, will re-connect in {}ms", deviceId, failureReason, nextBackOffMillis);
                pendingReconnect = streamExecutor.schedule(ofMillis(nextBackOffMillis), this::connect);
            }
        }

        private void closeConnection() {
            Connection closingConnection = connection;
            connection = null;
            if (closingConnection != null) {
                closingConnection.close();
            }
        }

        private void onDevice(MieleDevice device) {
            lastDevice = device;
            publish(device);
        }

        private void onActions(MieleActions actions) {
            lastActions = actions;
            publish(actions);
        }

        private void publish(MieleEvent event) {
            for (Subscription subscription : subscriptions) {
                subscription.deliver(event);
            }
        }

        /// One attempt to read the event stream: its call, the read loop over the call's response, and a ping check that fails the connection when no ping
        /// has arrived for [#PING_AGE_BEFORE_RECONNECT].
        private final class Connection {
            private final int id = connectionIdGenerator.incrementAndGet();
            private final Call call = client.newCall(newDeviceRequest(eventsUrl).header(ACCEPT, CONTENT_TYPE_EVENT_STREAM).build());
            private @Nullable Closeable pingMonitor;
            private Instant lastPingTime = dateTimeProvider.currentInstant();

            void open() {
                logger.info("[{}][{}] connecting to SSE stream and starting ping check every {}", deviceId, id, PING_CHECK_INTERVAL);
                pingMonitor = streamExecutor.scheduleAtFixedRate(PING_CHECK_INTERVAL, () -> runIfCurrent("check pings", this::checkPings));
                call.enqueue(new Callback() {
                    @Override
                    public void onFailure(Call failedCall, IOException e) {
                        logger.debug("[{}][{}] call failed", deviceId, id, e);
                        executeIfCurrent("handle event stream call failure", () -> onConnectionFailed(humanReadableMessage(e)));
                    }

                    @Override
                    public void onResponse(Call respondedCall, Response response) {
                        // ! HTTP thread !
                        try (ResponseBody responseBody = response.body()) {
                            if (!response.isSuccessful()) {
                                String failureReason = "failed to open SSE stream: response code " + response.code() + ", " + describe(responseBody);
                                executeIfCurrent("handle event stream rejection", () -> onConnectionFailed(failureReason));
                                return;
                            }
                            // a successful connection counts as a ping, so the first ping is due within PING_AGE_BEFORE_RECONNECT of it
                            executeIfCurrent("record event stream connection", () -> lastPingTime = dateTimeProvider.currentInstant());
                            logger.info("[{}][{}] successfully connected to SSE stream ({}), starting read loop", deviceId, id, response.code());
                            readLoop(responseBody);
                        }
                    }
                });
            }

            void close() {
                closeSafelyIfNotNull(logger, pingMonitor);
                // fails the read loop, and runIfCurrent drops the failure that follows
                call.cancel();
                logger.info("[{}][{}] SSE stream closed", deviceId, id);
            }

            private void readLoop(ResponseBody responseBody) {
                // Read the SSE events line by line
                // The SSE stream remains open indefinitely, so read it continuously
                try {
                    String eventType = null;
                    String line;
                    boolean lineDelivered = false;
                    while ((line = responseBody.source().readUtf8Line()) != null) {
                        if (!lineDelivered) {
                            lineDelivered = true;
                            executeIfCurrent("record event stream line", EventStream.this::onLineDelivered);
                        }
                        logger.debug("[{}][{}] event: {}", deviceId, id, line);
                        if (line.startsWith(EVENT_LINE_PREFIX)) {
                            eventType = line.substring(EVENT_LINE_PREFIX.length());
                        } else if (line.startsWith(DATA_LINE_PREFIX)) {
                            String eventData = line.substring(DATA_LINE_PREFIX.length());
                            if (eventType == null) {
                                logger.info("[{}][{}] unexpected event data, not preceded with event type: {}", deviceId, id, eventData);
                            } else {
                                String dataEventType = eventType;
                                executeIfCurrent("handle event stream event", () -> handleEvent(dataEventType, eventData));
                                eventType = null;
                            }
                        }
                    }
                    executeIfCurrent("handle event stream end", () -> onConnectionFailed("the server ended the stream"));
                } catch (IOException e) {
                    executeIfCurrent("handle event stream read failure", () -> {
                        logger.info("[{}][{}] read loop failed unexpectedly, will re-connect", deviceId, id, e);
                        onConnectionFailed(humanReadableMessage(e));
                    });
                }
            }

            private void handleEvent(String eventType, String data) {
                try {
                    switch (eventType) {
                        case DEVICE_EVENT -> onDevice(Json.parse(data, MieleDevice.class));
                        case ACTIONS_EVENT -> {
                            MieleActions actions = Json.parse(data, ACTIONS_BY_DEVICE_ID).get(deviceId);
                            if (actions != null) {
                                onActions(actions);
                            }
                        }
                        case PING_EVENT -> lastPingTime = dateTimeProvider.currentInstant();
                        default -> logger.debug("[{}][{}] ignoring unknown event type {}", deviceId, id, eventType);
                    }
                } catch (RuntimeException e) {
                    logger.info("[{}][{}] failed to parse '{}' event", deviceId, id, eventType, e);
                }
            }

            private void checkPings() {
                var pingAge = Duration.between(lastPingTime, dateTimeProvider.currentInstant());
                logger.debug("[{}][{}] lastPingTime: {}, pingAge: {}", deviceId, id, lastPingTime, pingAge);
                if (pingAge.compareTo(PING_AGE_BEFORE_RECONNECT) > 0) {
                    onConnectionFailed("re-connected or last ping received " + pingAge + " ago (>" + PING_AGE_BEFORE_RECONNECT + ")");
                } else {
                    // the next failure of the stream starts the back-off from INITIAL_RECONNECT_DELAY again
                    reconnectBackoff.reset();
                }
            }

            /// Submits [#runIfCurrent] to the stream's executor, and drops `action` once that executor has shut down.
            private void executeIfCurrent(String taskName, Runnable action) {
                if (!streamExecutor.tryExecute(taskName, () -> runIfCurrent(taskName, action))) {
                    logger.debug("[{}][{}] executor shut down, dropping '{}'", deviceId, id, taskName);
                }
            }

            /// Runs `action` if this is the stream's [#connection] and the dishwasher has not stopped, so that the callbacks of a closed or replaced
            /// connection, and those [#doStop()] drains from the executor, change nothing.
            private void runIfCurrent(String taskName, Runnable action) {
                if (connection == this) {
                    ifNotStopped(action);
                } else {
                    logger.debug("[{}][{}] ignoring '{}' of a connection other than the stream's current one", deviceId, id, taskName);
                }
            }

            private static String describe(ResponseBody responseBody) {
                try {
                    return "body: " + responseBody.string();
                } catch (IOException | RuntimeException e) {
                    return "failed getting response body: " + humanReadableMessage(e);
                }
            }
        }
    }
}
