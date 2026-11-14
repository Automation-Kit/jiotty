package net.yudichev.jiotty.connector.miele;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.common.lang.MutableReference;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.security.OAuth2TokenManager;
import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.Dispatcher;
import okhttp3.EventListener;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.Okio;
import okio.Source;
import okio.Timeout;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;
import static java.nio.charset.StandardCharsets.UTF_8;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static net.yudichev.jiotty.common.rest.BearerTokens.BEARER_PREFIX;
import static net.yudichev.jiotty.common.rest.ContentTypes.CONTENT_TYPE_EVENT_STREAM;
import static net.yudichev.jiotty.common.rest.HttpStatuses.INTERNAL_SERVER_ERROR_500;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static net.yudichev.jiotty.connector.miele.MieleDishwasherImpl.ACTIONS_EVENT;
import static net.yudichev.jiotty.connector.miele.MieleDishwasherImpl.DATA_LINE_PREFIX;
import static net.yudichev.jiotty.connector.miele.MieleDishwasherImpl.DEVICE_EVENT;
import static net.yudichev.jiotty.connector.miele.MieleDishwasherImpl.EVENTS_ENDPOINT;
import static net.yudichev.jiotty.connector.miele.MieleDishwasherImpl.EVENT_LINE_PREFIX;
import static net.yudichev.jiotty.connector.miele.MieleDishwasherImpl.INITIAL_RECONNECT_DELAY;
import static net.yudichev.jiotty.connector.miele.MieleDishwasherImpl.MAX_RECONNECT_DELAY;
import static net.yudichev.jiotty.connector.miele.MieleDishwasherImpl.PING_AGE_BEFORE_RECONNECT;
import static net.yudichev.jiotty.connector.miele.MieleDishwasherImpl.PING_CHECK_INTERVAL;
import static net.yudichev.jiotty.connector.miele.MieleDishwasherImpl.PING_EVENT;
import static net.yudichev.jiotty.connector.miele.MieleDishwasherImpl.devicePath;
import static net.yudichev.jiotty.connector.miele.MieleStreamConnected.STREAM_CONNECTED;
import static net.yudichev.jiotty.connector.miele.MieleStreamDisconnected.STREAM_DISCONNECTED;
import static net.yudichev.jiotty.connector.miele.MieleTestResources.RUNNING_DEVICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/// Tests of the [MieleDishwasherImpl] event stream on a [ProgrammableClock]. The HTTP calls run on the clock's executor thread, so a tick completes every
/// call it starts, except in the tests that hold calls back in a [ControlledExecutorService].
@ExtendWith(MockitoExtension.class)
class MieleDishwasherImplEventStreamTest {
    private static final String DEVICE_ID = "deviceId";
    private static final String BASE_PATH = "/v1";
    private static final String BASE_URL = "https://miele.invalid" + BASE_PATH;
    private static final String EVENTS_PATH = BASE_PATH + devicePath(DEVICE_ID, EVENTS_ENDPOINT);
    private static final String LOOPBACK_ADDRESS = "127.0.0.1";
    private static final String INITIAL_TOKEN = "token-1";
    private static final String REFRESHED_TOKEN = "token-2";
    private static final String PING_EVENT_PAYLOAD = sseEvent(PING_EVENT, "ping");
    private static final MediaType EVENT_STREAM_MEDIA_TYPE = MediaType.get(CONTENT_TYPE_EVENT_STREAM);
    /// Room for a dozen reconnections at the delays the back-off starts with.
    private static final Duration RECONNECTION_PERIOD = INITIAL_RECONNECT_DELAY.multipliedBy(100);
    /// The back-off draws each delay from at most one and a half times [MieleDishwasherImpl#MAX_RECONNECT_DELAY].
    private static final Duration PAST_ANY_RECONNECT_DELAY = MAX_RECONNECT_DELAY.multipliedBy(2);
    /// The first ping check after [MieleDishwasherImpl#PING_AGE_BEFORE_RECONNECT] without a response, counted from the connection.
    private static final Duration FIRST_PING_TIMEOUT = PING_CHECK_INTERVAL.multipliedBy(PING_AGE_BEFORE_RECONNECT.dividedBy(PING_CHECK_INTERVAL) + 1);
    /// [#FIRST_PING_TIMEOUT] plus half a check interval for the reconnection that follows it.
    private static final Duration PAST_FIRST_PING_TIMEOUT = FIRST_PING_TIMEOUT.plus(PING_CHECK_INTERVAL.dividedBy(2));

    @RegisterExtension
    static final WireMockExtension wireMock = WireMockExtension.newInstance()
                                                               .options(wireMockConfig().bindAddress(LOOPBACK_ADDRESS).dynamicPort().dynamicHttpsPort())
                                                               .build();

    private final List<Request> eventRequests = new ArrayList<>();
    private final List<MieleEvent> receivedEvents = new ArrayList<>();
    private ProgrammableClock clock;
    @Mock
    private OAuth2TokenManager tokenManager;
    @Mock
    private Closeable tokenSubscription;
    private Consumer<? super AuthState> tokenStateHandler;
    private int eventResponseCode = OK_200;
    private String eventResponseBody = PING_EVENT_PAYLOAD;
    private @Nullable IOException eventResponseFailure;
    private boolean eventResponseBodyFailsAfterContent;
    /// Run on the read that follows an event response's content, before that read reports the end of the response or fails; `null` for none.
    private @Nullable Runnable eventResponseEndHook;
    private @Nullable MieleDishwasherImpl startedDishwasher;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        when(tokenManager.subscribeToAccessTokenState(any())).thenAnswer(invocation -> {
            tokenStateHandler = invocation.getArgument(0);
            tokenStateHandler.accept(new AuthState.Success(INITIAL_TOKEN));
            return tokenSubscription;
        });
    }

    @AfterEach
    void tearDown() {
        if (startedDishwasher != null) {
            startedDishwasher.stop();
        }
    }

    @Test
    void whenStreamReconnectsAfterTokenRefresh_thenReconnectionCarriesCurrentToken() {
        MieleDishwasherImpl dishwasher = startWithRecordedEventRequests();
        dishwasher.subscribeToEvents(_ -> {});
        clock.tick();
        assertThat(eventRequests).singleElement()
                                 .satisfies(request -> assertThat(request.header(AUTHORIZATION)).isEqualTo(BEARER_PREFIX + INITIAL_TOKEN));

        tokenStateHandler.accept(new AuthState.Success(REFRESHED_TOKEN));
        clock.advanceTimeAndTick(RECONNECTION_PERIOD);

        assertThat(eventRequests).hasSizeGreaterThan(1)
                                 .last()
                                 .satisfies(request -> assertThat(request.header(AUTHORIZATION)).isEqualTo(BEARER_PREFIX + REFRESHED_TOKEN));
    }

    @Test
    void whenServerEndsStream_thenReconnectsWithoutWaitingForPings() {
        MieleDishwasherImpl dishwasher = startWithRecordedEventRequests();
        dishwasher.subscribeToEvents(_ -> {});
        clock.tick();
        assertThat(eventRequests).hasSize(1);

        clock.advanceTimeAndTick(RECONNECTION_PERIOD);

        assertThat(eventRequests).hasSizeGreaterThan(1);
    }

    @Test
    void whenServerKeepsEndingStreamAfterDeliveringEvents_thenReconnectionsBackOff() {
        MieleDishwasherImpl dishwasher = startWithRecordedEventRequests();
        dishwasher.subscribeToEvents(_ -> {});
        clock.tick();

        clock.advanceTimeAndTick(RECONNECTION_PERIOD);

        // each delay is half as long again as the one before, so the period holds about a dozen connections rather than one every INITIAL_RECONNECT_DELAY
        assertThat(eventRequests).hasSizeBetween(2, 20);
    }

    @Test
    void whenConnectionPassesPingCheck_thenNextFailureReconnectsAfterInitialDelay() {
        eventResponseCode = INTERNAL_SERVER_ERROR_500;
        var callExecutor = new ControlledExecutorService(clock);
        MieleDishwasherImpl dishwasher = start(BASE_URL,
                                               builder -> builder.dispatcher(new Dispatcher(callExecutor))
                                                                 .addInterceptor(this::respondToEventRequest));
        dishwasher.subscribeToEvents(_ -> {});
        clock.tick();
        // failed connections grow the back-off well beyond its initial delay
        clock.advanceTimeAndTick(RECONNECTION_PERIOD);

        callExecutor.holding = true;
        clock.advanceTimeAndTick(MAX_RECONNECT_DELAY);
        assertThat(callExecutor.heldTaskTimes).hasSize(1);
        // the held connection passes its first ping checks, then fails one for want of a ping
        clock.advanceTimeAndTick(PAST_FIRST_PING_TIMEOUT);

        assertThat(callExecutor.heldTaskTimes).hasSize(2);
        Instant pingTimeout = callExecutor.heldTaskTimes.getFirst().plus(FIRST_PING_TIMEOUT);
        // the back-off draws each delay from between half and one and a half times its current interval
        assertThat(Duration.between(pingTimeout, callExecutor.heldTaskTimes.get(1))).isLessThanOrEqualTo(INITIAL_RECONNECT_DELAY.multipliedBy(3).dividedBy(2));
    }

    @Test
    void whenSubscriptionClosedWhileReconnectionPending_thenNoFurtherConnectionAttempt() {
        eventResponseCode = INTERNAL_SERVER_ERROR_500;
        MieleDishwasherImpl dishwasher = startWithRecordedEventRequests();
        Closeable subscription = dishwasher.subscribeToEvents(_ -> {});
        clock.tick();
        assertThat(eventRequests).hasSize(1);

        subscription.close();
        clock.advanceTimeAndTick(PAST_ANY_RECONNECT_DELAY);

        assertThat(eventRequests).hasSize(1);
    }

    @Test
    void whenSubscriptionClosedBeforeFirstConnection_thenNoConnectionAttempt() {
        MieleDishwasherImpl dishwasher = startWithRecordedEventRequests();
        dishwasher.subscribeToEvents(_ -> {}).close();
        clock.advanceTimeAndTick(PAST_ANY_RECONNECT_DELAY);

        assertThat(eventRequests).isEmpty();
    }

    @Test
    void whenStreamFailsBeforeEverConnecting_thenSubscriberIsToldItIsDisconnected() {
        eventResponseCode = INTERNAL_SERVER_ERROR_500;
        MieleDishwasherImpl dishwasher = startWithRecordedEventRequests();
        dishwasher.subscribeToEvents(receivedEvents::add);
        clock.tick();
        assertThat(receivedEvents).containsExactly(STREAM_DISCONNECTED);

        clock.advanceTimeAndTick(RECONNECTION_PERIOD);
        assertThat(eventRequests).hasSizeGreaterThan(1);
        assertThat(receivedEvents).containsExactly(STREAM_DISCONNECTED);
    }

    @Test
    void whenConnectionFails_thenReconnects() {
        eventResponseFailure = new IOException("connection refused");
        MieleDishwasherImpl dishwasher = startWithRecordedEventRequests();
        dishwasher.subscribeToEvents(receivedEvents::add);
        clock.tick();
        assertThat(eventRequests).hasSize(1);
        assertThat(receivedEvents).containsExactly(STREAM_DISCONNECTED);

        clock.advanceTimeAndTick(RECONNECTION_PERIOD);

        assertThat(eventRequests).hasSizeGreaterThan(1);
    }

    @Test
    void whenReadingStreamFails_thenReconnects() {
        eventResponseBodyFailsAfterContent = true;
        MieleDishwasherImpl dishwasher = startWithRecordedEventRequests();
        dishwasher.subscribeToEvents(receivedEvents::add);
        clock.tick();
        assertThat(receivedEvents).containsExactly(STREAM_CONNECTED, STREAM_DISCONNECTED);

        clock.advanceTimeAndTick(RECONNECTION_PERIOD);

        assertThat(eventRequests).hasSizeGreaterThan(1);
    }

    @Test
    void whenSubscriberClosesStreamOnConnection_thenStreamDeliversNothingFurther() {
        MieleDishwasherImpl dishwasher = startWithRecordedEventRequests();
        var subscription = new MutableReference<Closeable>();
        subscription.set(dishwasher.subscribeToEvents(event -> {
            receivedEvents.add(event);
            subscription.get().close();
        }));
        clock.tick();
        clock.advanceTimeAndTick(PAST_ANY_RECONNECT_DELAY);

        assertThat(receivedEvents).containsExactly(STREAM_CONNECTED);
        assertThat(eventRequests).hasSize(1);
    }

    @Test
    void whenStreamConnects_thenSubscriberIsToldItIsConnected() {
        MieleDishwasherImpl dishwasher = startWithRecordedEventRequests();
        dishwasher.subscribeToEvents(receivedEvents::add);
        clock.tick();

        assertThat(receivedEvents).startsWith(STREAM_CONNECTED);
    }

    @Test
    void whenSubscriberJoinsOpenStream_thenItFirstReceivesTheStreamsLatestState() {
        var actions = MieleActions.builder().setPowerOnAvailable(true).setPowerOffAvailable(false).build();
        eventResponseBody = sseEvent(DEVICE_EVENT, compactRunningDeviceJson())
                            + sseEvent(ACTIONS_EVENT, Json.stringify(Map.of(DEVICE_ID, actions)))
                            + PING_EVENT_PAYLOAD;
        MieleDishwasherImpl dishwasher = startWithRecordedEventRequests();
        dishwasher.subscribeToEvents(_ -> {});
        clock.tick();

        dishwasher.subscribeToEvents(receivedEvents::add);
        clock.tick();

        assertThat(receivedEvents).satisfiesExactly(event -> assertThat(event).isEqualTo(STREAM_DISCONNECTED),
                                                    event -> assertThat(event).isInstanceOf(MieleDevice.class),
                                                    event -> assertThat(event).isEqualTo(actions));
    }

    @Test
    void whenSubscriberJoinsConnectedStream_thenItFirstReceivesStreamConnected() {
        MieleDishwasherImpl dishwasher = startWithRecordedEventRequests();
        // the second subscriber joins after the stream has delivered its lines and before it reaches the end of the stream
        eventResponseEndHook = () -> dishwasher.subscribeToEvents(receivedEvents::add);
        dishwasher.subscribeToEvents(_ -> {});
        clock.tick();

        assertThat(receivedEvents).containsExactly(STREAM_CONNECTED, STREAM_DISCONNECTED);
    }

    @Test
    void whenStreamCarriesEventsItCannotUse_thenItSkipsThemWithoutFailingTheConnection() {
        var otherDevicesActions = MieleActions.builder().setPowerOnAvailable(true).setPowerOffAvailable(true).build();
        eventResponseBody = DATA_LINE_PREFIX + "data without an event type\n\n"
                            + sseEvent("unknown", "anything")
                            + sseEvent(DEVICE_EVENT, "{not json}")
                            + sseEvent(ACTIONS_EVENT, Json.stringify(Map.of("another device", otherDevicesActions)))
                            + PING_EVENT_PAYLOAD;
        MieleDishwasherImpl dishwasher = startWithRecordedEventRequests();
        dishwasher.subscribeToEvents(receivedEvents::add);
        clock.tick();

        assertThat(receivedEvents).containsExactly(STREAM_CONNECTED, STREAM_DISCONNECTED);
    }

    @Test
    void whenRejectionBodyCannotBeRead_thenReconnects() {
        eventResponseCode = INTERNAL_SERVER_ERROR_500;
        eventResponseBodyFailsAfterContent = true;
        MieleDishwasherImpl dishwasher = startWithRecordedEventRequests();
        dishwasher.subscribeToEvents(receivedEvents::add);
        clock.tick();
        assertThat(receivedEvents).containsExactly(STREAM_DISCONNECTED);

        clock.advanceTimeAndTick(RECONNECTION_PERIOD);

        assertThat(eventRequests).hasSizeGreaterThan(1);
    }

    @Test
    void whenSubscriberFails_thenOtherSubscribersStillReceiveEvents() {
        MieleDishwasherImpl dishwasher = startWithRecordedEventRequests();
        dishwasher.subscribeToEvents(_ -> {
            throw new IllegalStateException("subscriber failure");
        });
        dishwasher.subscribeToEvents(receivedEvents::add);
        clock.tick();

        assertThat(receivedEvents).containsExactly(STREAM_CONNECTED, STREAM_DISCONNECTED);
    }

    @Test
    void whenTwoSubscribers_thenTheyShareOneStreamUntilBothClose() {
        MieleDishwasherImpl dishwasher = startWithRecordedEventRequests();
        Closeable firstSubscription = dishwasher.subscribeToEvents(_ -> {});
        Closeable secondSubscription = dishwasher.subscribeToEvents(receivedEvents::add);
        clock.tick();
        assertThat(eventRequests).hasSize(1);

        firstSubscription.close();
        clock.advanceTimeAndTick(RECONNECTION_PERIOD);
        int requestCountWhileSecondSubscribed = eventRequests.size();
        assertThat(requestCountWhileSecondSubscribed).isGreaterThan(1);

        secondSubscription.close();
        clock.advanceTimeAndTick(PAST_ANY_RECONNECT_DELAY);
        assertThat(eventRequests).hasSize(requestCountWhileSecondSubscribed);
    }

    @Test
    void whenRestarted_thenNewSubscriptionConnects() {
        MieleDishwasherImpl dishwasher = startWithRecordedEventRequests();
        dishwasher.subscribeToEvents(_ -> {});
        clock.tick();
        dishwasher.stop();

        dishwasher.start();
        dishwasher.subscribeToEvents(_ -> {});
        clock.tick();

        assertThat(eventRequests).hasSize(2);
    }

    @Test
    void whenSubscriptionTakenBeforeRestartIsClosed_thenNextSubscriberJoinsCurrentStream() {
        MieleDishwasherImpl dishwasher = startWithRecordedEventRequests();
        Closeable subscriptionBeforeRestart = dishwasher.subscribeToEvents(_ -> {});
        clock.tick();
        dishwasher.stop();
        dishwasher.start();
        dishwasher.subscribeToEvents(_ -> {});
        clock.tick();
        assertThat(eventRequests).hasSize(2);

        subscriptionBeforeRestart.close();
        dishwasher.subscribeToEvents(_ -> {});
        clock.tick();

        assertThat(eventRequests).hasSize(2);
    }

    @Test
    void whenStreamDeliversNoPings_thenReconnects() {
        var heldCallExecutor = new ControlledExecutorService(clock);
        heldCallExecutor.holding = true;
        MieleDishwasherImpl dishwasher = startWithCallsIn(heldCallExecutor);
        dishwasher.subscribeToEvents(receivedEvents::add);
        clock.tick();
        assertThat(heldCallExecutor.heldTasks).hasSize(1);

        clock.advanceTimeAndTick(PAST_FIRST_PING_TIMEOUT);

        assertThat(heldCallExecutor.heldTasks).hasSize(2);
        assertThat(receivedEvents).containsExactly(STREAM_DISCONNECTED);
    }

    @Test
    void whenClosedCallCompletesAfterReconnection_thenItsOutcomeIsIgnored() {
        var heldCallExecutor = new ControlledExecutorService(clock);
        heldCallExecutor.holding = true;
        MieleDishwasherImpl dishwasher = startWithCallsIn(heldCallExecutor);
        dishwasher.subscribeToEvents(_ -> {});
        clock.tick();
        clock.advanceTimeAndTick(PAST_FIRST_PING_TIMEOUT);
        assertThat(heldCallExecutor.heldTasks).hasSize(2);

        // the call the ping timeout cancelled completes now, failing as cancelled
        heldCallExecutor.heldTasks.getFirst().run();
        clock.advanceTimeAndTick(PING_CHECK_INTERVAL.dividedBy(4));

        assertThat(heldCallExecutor.heldTasks).hasSize(2);
    }

    @Test
    void whenCallCompletesAfterStop_thenItsOutcomeIsDropped() {
        var heldCallExecutor = new ControlledExecutorService(clock);
        heldCallExecutor.holding = true;
        MieleDishwasherImpl dishwasher = startWithCallsIn(heldCallExecutor);
        dishwasher.subscribeToEvents(_ -> {});
        clock.tick();
        dishwasher.stop();

        assertThatCode(() -> heldCallExecutor.heldTasks.getFirst().run()).doesNotThrowAnyException();
    }

    @Test
    void whenStopped_thenOpenEventCallIsCancelled() {
        var heldCallExecutor = new ControlledExecutorService(clock);
        heldCallExecutor.holding = true;
        var cancelledCalls = new ArrayList<Call>();
        MieleDishwasherImpl dishwasher = start(BASE_URL, builder -> builder.dispatcher(new Dispatcher(heldCallExecutor))
                                                                           .eventListener(new EventListener() {
                                                                               @Override
                                                                               public void canceled(Call call) {
                                                                                   cancelledCalls.add(call);
                                                                               }
                                                                           }));
        dishwasher.subscribeToEvents(_ -> {});
        clock.tick();

        dishwasher.stop();

        assertThat(cancelledCalls).hasSize(1);
    }

    @Test
    void whenStoppedDuringReconnectionDelay_thenNoFurtherConnectionAttempt() {
        eventResponseCode = INTERNAL_SERVER_ERROR_500;
        MieleDishwasherImpl dishwasher = startWithRecordedEventRequests();
        dishwasher.subscribeToEvents(_ -> {});
        clock.tick();
        assertThat(eventRequests).hasSize(1);

        dishwasher.stop();
        clock.advanceTimeAndTick(PAST_ANY_RECONNECT_DELAY);

        assertThat(eventRequests).hasSize(1);
    }

    @Test
    void whenStoppedBeforeQueuedConnectionTasksRun_thenSubscriberReceivesNothing() {
        MieleDishwasherImpl dishwasher = startWithRecordedEventRequests();
        dishwasher.subscribeToEvents(receivedEvents::add);

        // the stop runs the queued tasks: the subscription opens the stream, whose response tasks queue behind it
        dishwasher.stop();

        assertThat(eventRequests).hasSize(1);
        assertThat(receivedEvents).isEmpty();
    }

    @Test
    void whenResubscribedOverTls_thenNewStreamUsesNewHttp11Connection() {
        wireMock.stubFor(get(urlPathEqualTo(EVENTS_PATH)).willReturn(aResponse().withStatus(OK_200)
                                                                                .withHeader(CONTENT_TYPE, CONTENT_TYPE_EVENT_STREAM)
                                                                                .withBody(PING_EVENT_PAYLOAD)));
        var connectionProtocols = new ArrayList<Protocol>();
        var tcpConnections = new ArrayList<InetSocketAddress>();
        var trustManager = new AnyCertificateTrustManager();
        SSLContext sslContext = getAsUnchecked(() -> SSLContext.getInstance("TLS"));
        asUnchecked(() -> sslContext.init(null, new TrustManager[]{trustManager}, null));
        MieleDishwasherImpl dishwasher = start("https://" + LOOPBACK_ADDRESS + ':' + wireMock.getRuntimeInfo().getHttpsPort() + BASE_PATH,
                                               builder -> builder.dispatcher(new Dispatcher(newDirectExecutorService()))
                                                                 .sslSocketFactory(sslContext.getSocketFactory(), trustManager)
                                                                 .hostnameVerifier((_, _) -> true)
                                                                 .eventListener(new EventListener() {
                                                                     @Override
                                                                     public void connectStart(Call call, InetSocketAddress inetSocketAddress, Proxy proxy) {
                                                                         tcpConnections.add(inetSocketAddress);
                                                                     }

                                                                     @Override
                                                                     public void connectionAcquired(Call call, Connection connection) {
                                                                         connectionProtocols.add(connection.protocol());
                                                                     }
                                                                 }));

        Closeable firstSubscription = dishwasher.subscribeToEvents(_ -> {});
        clock.tick();
        firstSubscription.close();
        dishwasher.subscribeToEvents(_ -> {});
        clock.tick();

        assertThat(connectionProtocols).containsExactly(Protocol.HTTP_1_1, Protocol.HTTP_1_1);
        assertThat(tcpConnections).hasSize(2);
    }

    private MieleDishwasherImpl startWithRecordedEventRequests() {
        return start(BASE_URL,
                     builder -> builder.dispatcher(new Dispatcher(newDirectExecutorService()))
                                       .addInterceptor(this::respondToEventRequest));
    }

    private MieleDishwasherImpl startWithCallsIn(ControlledExecutorService callExecutor) {
        return start(BASE_URL, builder -> builder.dispatcher(new Dispatcher(callExecutor)));
    }

    private MieleDishwasherImpl start(String baseUrl, Consumer<? super OkHttpClient.Builder> streamingClientCustomiser) {
        var dishwasher = new MieleDishwasherImpl(DEVICE_ID,
                                                 tokenManager,
                                                 clock,
                                                 clock,
                                                 RetryableOperationExecutor.noRetries(),
                                                 baseUrl,
                                                 _ -> {},
                                                 streamingClientCustomiser,
                                                 1);
        startedDishwasher = dishwasher;
        dishwasher.start();
        return dishwasher;
    }

    private Response respondToEventRequest(Interceptor.Chain chain) throws IOException {
        Request request = chain.request();
        eventRequests.add(request);
        if (eventResponseFailure != null) {
            throw eventResponseFailure;
        }
        return new Response.Builder().request(request)
                                     .protocol(Protocol.HTTP_1_1)
                                     .code(eventResponseCode)
                                     .message("test")
                                     .body(ResponseBody.create(Okio.buffer(new ScriptedContentSource(eventResponseBody,
                                                                                                     eventResponseBodyFailsAfterContent,
                                                                                                     eventResponseEndHook)),
                                                               EVENT_STREAM_MEDIA_TYPE,
                                                               -1))
                                     .build();
    }

    private static String compactRunningDeviceJson() {
        return Json.stringify(MieleTestResources.parse(RUNNING_DEVICE, JsonNode.class));
    }

    private static String sseEvent(String eventType, String data) {
        return EVENT_LINE_PREFIX + eventType + '\n' + DATA_LINE_PREFIX + data + "\n\n";
    }

    /// A source that delivers its content in one read, then on the next read runs its end hook and either reports the end of the stream or, as a
    /// connection reset during a stream does, fails with an [IOException].
    private static final class ScriptedContentSource implements Source {
        private final byte[] content;
        private final boolean failsAfterContent;
        private final @Nullable Runnable endHook;
        private boolean contentDelivered;

        /// @param endHook run once the content has been read, `null` for none
        ScriptedContentSource(String content, boolean failsAfterContent, @Nullable Runnable endHook) {
            this.content = content.getBytes(UTF_8);
            this.failsAfterContent = failsAfterContent;
            this.endHook = endHook;
        }

        @Override
        public long read(Buffer sink, long byteCount) throws IOException {
            if (!contentDelivered) {
                contentDelivered = true;
                sink.write(content);
                return content.length;
            }
            if (endHook != null) {
                endHook.run();
            }
            if (failsAfterContent) {
                throw new IOException("connection reset");
            }
            return -1;
        }

        @Override
        public Timeout timeout() {
            return Timeout.NONE;
        }

        @Override
        public void close() {
        }
    }

    /// An [ExecutorService] that runs each submitted task straight away while [#holding] is `false`, and otherwise keeps it in [#heldTasks] without running
    /// it, recording the [ProgrammableClock] time it was submitted at in [#heldTaskTimes].
    private static final class ControlledExecutorService extends AbstractExecutorService {
        private final ProgrammableClock clock;
        private final List<Runnable> heldTasks = new ArrayList<>();
        private final List<Instant> heldTaskTimes = new ArrayList<>();
        private boolean holding;

        ControlledExecutorService(ProgrammableClock clock) {
            this.clock = checkNotNull(clock);
        }

        @Override
        public void execute(Runnable command) {
            if (holding) {
                heldTasks.add(command);
                heldTaskTimes.add(clock.currentInstant());
            } else {
                command.run();
            }
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    /// An [X509TrustManager] that accepts every certificate, the self-signed one of the [WireMockExtension] server included.
    private static final class AnyCertificateTrustManager implements X509TrustManager {
        private static final X509Certificate[] NO_ISSUERS = new X509Certificate[0];

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return NO_ISSUERS;
        }
    }
}
