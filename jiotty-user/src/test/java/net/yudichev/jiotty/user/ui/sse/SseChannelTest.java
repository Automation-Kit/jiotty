package net.yudichev.jiotty.user.ui.sse;

import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.reflect.TypeToken;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.adminalerts.TestAdminAlertService;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.common.lang.MutableReference;
import net.yudichev.jiotty.user.ui.sse.testing.CapturingServletOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static net.yudichev.jiotty.user.ui.sse.testing.SseFrames.dataOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// Transport-level behaviour of the shared SSE channel: headers, wire format, hello and heartbeat frames, per-client and broadcast delivery, the client
/// capacity cap, and the close paths. What is streamed over a channel is covered by each feature's own test.
@ExtendWith(MockitoExtension.class)
class SseChannelTest {
    /// Rooted at [Object], as every channel's writer is, so each frame is serialised from its own type.
    private static final ObjectWriter WRITER = Json.createWriterFor(new TypeToken<>() {});

    private final TestAdminAlertService alertService = new TestAdminAlertService();
    private ProgrammableClock clock;
    private SchedulingExecutor executor;
    private SseChannel channel;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        executor = clock.createSingleThreadedSchedulingExecutor("test");
        channel = createChannel(SseChannel.UNBOUNDED);
        clock.tick();
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.close();
            clock.tick();
        }
    }

    @Test
    void openWritesEventStreamHeaders() {
        SseCapture capture = openClient();

        verify(capture.response).setContentType("text/event-stream");
        verify(capture.response).setCharacterEncoding("utf-8");
        verify(capture.response).setHeader("Cache-Control", "no-cache");
        verify(capture.response).setHeader("Connection", "keep-alive");
        verify(capture.response).setHeader("X-Accel-Buffering", "no");
        verify(capture.response).setHeader("X-Client-Id-Seq-Num", "1");
        verify(capture.asyncContext).setTimeout(0);
    }

    @Test
    void helloEventContainsClientIdSeqNumAndServerTime() {
        SseCapture capture = openClient();

        String output = capture.output();
        assertThat(output).startsWith("retry: 3000\n\n").contains("event: hello");
        HelloData hello = dataOf(output, "hello", new TypeToken<>() {});
        assertThat(hello.clientIdSeqNum()).isEqualTo(1);
        assertThat(hello.serverTime()).isEqualTo(clock.currentInstant());
    }

    @Test
    void clientIdsAreSequential() {
        SseCapture capture1 = openClient("host1", 1111);
        SseCapture capture2 = openClient("host2", 2222);

        HelloData firstHello = dataOf(capture1.output(), "hello", new TypeToken<>() {});
        HelloData secondHello = dataOf(capture2.output(), "hello", new TypeToken<>() {});
        assertThat(secondHello.clientIdSeqNum()).isEqualTo(firstHello.clientIdSeqNum() + 1);
    }

    @Test
    void onOpenSinkDeliversTheInitialImageToThatClientAlone() {
        SseCapture capture1 = openClient("host1", 1111);
        capture1.reset();

        SseCapture capture2 = openClient("host2", 2222, sink -> sink.send("image", Map.of("value", "initial")));

        assertThat(capture2.output()).contains("event: image").contains("\"initial\"");
        assertThat(capture1.output()).doesNotContain("event: image");
    }

    @Test
    void sinkHandedToOnOpenKeepsSendingToItsClientAfterTheInitialImage() {
        var sinkRef = new MutableReference<SseChannel.SseStream>();
        SseCapture capture = openClient("host1", 1111, sinkRef::set);
        capture.reset();

        sinkRef.get().send("later", Map.of("value", 42));
        clock.tick();

        assertThat(capture.output()).contains("event: later").contains("42");
    }

    @Test
    void broadcastReachesEveryConnectedClient() {
        SseCapture capture1 = openClient("host1", 1111);
        SseCapture capture2 = openClient("host2", 2222);
        capture1.reset();
        capture2.reset();

        channel.broadcast("update", Map.of("value", "v"));
        clock.tick();

        assertThat(capture1.output()).contains("event: update");
        assertThat(capture2.output()).contains("event: update");
    }

    @Test
    void heartbeatIsSentEveryHeartbeatPeriodWithTheCurrentServerTime() {
        SseCapture capture = openClient();
        capture.reset();

        clock.advanceTimeAndTick(SseChannel.HEARTBEAT_PERIOD);
        var firstPingTime = clock.currentInstant();
        PingData firstPing = dataOf(capture.output(), "ping", new TypeToken<>() {});
        assertThat(firstPing.serverTime()).isEqualTo(firstPingTime);

        capture.reset();
        clock.advanceTimeAndTick(SseChannel.HEARTBEAT_PERIOD);
        var secondPingTime = clock.currentInstant();
        PingData secondPing = dataOf(capture.output(), "ping", new TypeToken<>() {});
        assertThat(secondPing.serverTime()).isEqualTo(secondPingTime);
        assertThat(secondPingTime).isNotEqualTo(firstPingTime);
    }

    @Test
    void clientDisconnectStopsDelivery() {
        SseCapture capture = openClient();
        capture.reset();

        asUnchecked(() -> capture.asyncListener.get().onComplete(null));
        clock.tick();

        channel.broadcast("update", Map.of("value", "v"));
        clock.tick();

        assertThat(capture.output()).doesNotContain("event: update");
    }

    @ParameterizedTest
    @MethodSource
    void everyContainerEndOfLifeEventStopsDelivery(ContainerEvent event) {
        SseCapture capture = openClient();
        capture.reset();

        asUnchecked(() -> event.deliverTo(capture.asyncListener.get(), capture.asyncContext));
        clock.tick();

        channel.broadcast("update", Map.of("value", "v"));
        clock.tick();

        assertThat(capture.output()).doesNotContain("event: update");
        assertThat(channel.clientCount()).isZero();
    }

    static Stream<Arguments> everyContainerEndOfLifeEventStopsDelivery() {
        return Stream.of(arguments(named("onTimeout", (ContainerEvent) (listener, _) -> listener.onTimeout(null))),
                         arguments(named("onError",
                                         (ContainerEvent) (listener, context) ->
                                                 listener.onError(new AsyncEvent(context, new IllegalStateException("connection reset"))))));
    }

    @Test
    void aStreamStaysDeliverableWhenTheContainerRestartsAsync() {
        SseCapture capture = openClient();
        capture.reset();

        asUnchecked(() -> capture.asyncListener.get().onStartAsync(null));
        clock.tick();

        channel.broadcast("update", Map.of("value", "v"));
        clock.tick();

        assertThat(capture.output()).contains("event: update");
    }

    @Test
    void aClientWhoseHeadersCannotBeWrittenGivesItsCapacitySlotBack() {
        var capture = new SseCapture("host1", 1111);
        asUnchecked(() -> doThrow(new IOException("gone")).when(capture.response).flushBuffer());

        assertThatThrownBy(() -> channel.open(capture.asyncContext, capture.response, capture.onStreamClosed, _ -> {}))
                .isInstanceOf(IOException.class);
        clock.tick();

        assertThat(channel.clientCount()).isZero();
    }

    @Test
    void aClientWhoseRegistrationCannotBeScheduledGivesItsCapacitySlotBack(@Mock SchedulingExecutor rejectingExecutor) {
        // A real SchedulingExecutor rejects work once its queue is full or it has shut down; the deterministic one accepts everything, so stand one in.
        when(rejectingExecutor.scheduleAtFixedRate(any(), any())).thenReturn(Closeable.noop());
        doThrow(new RejectedExecutionException("queue full")).when(rejectingExecutor).execute(any(), any());
        var rejectingChannel = new SseChannel("rejecting", rejectingExecutor, clock, WRITER, alertService, 1);
        rejectingChannel.start();
        var capture = new SseCapture("host1", 1111);

        assertThatThrownBy(() -> rejectingChannel.open(capture.asyncContext, capture.response, capture.onStreamClosed, _ -> {}))
                .isInstanceOf(RejectedExecutionException.class);

        assertThat(rejectingChannel.clientCount()).isZero();
        verify(capture.onStreamClosed).run();
    }

    @Test
    void aClientWhoseListenerCannotBeRegisteredIsDropped() {
        var capture = new SseCapture("host1", 1111);
        doThrow(new IllegalStateException("async already complete")).when(capture.asyncContext).addListener(any(AsyncListener.class));

        getAsUnchecked(() -> channel.open(capture.asyncContext, capture.response, capture.onStreamClosed, _ -> {}));
        clock.tick();

        verify(capture.onStreamClosed).run();
        assertThat(channel.clientCount()).isZero();
    }

    @Test
    void aClientWhoseInitialImageThrowsIsDropped() {
        SseCapture healthy = openClient("host1", 1111);
        var capture = new SseCapture("host2", 2222);

        getAsUnchecked(() -> channel.open(capture.asyncContext,
                                          capture.response,
                                          capture.onStreamClosed,
                                          _ -> {throw new IllegalStateException("feature blew up");}));
        clock.tick();
        capture.reset();
        healthy.reset();

        channel.broadcast("update", Map.of("value", "v"));
        clock.tick();

        assertThat(capture.output()).as("a client whose initial image failed is no longer in the broadcast set").isEmpty();
        assertThat(healthy.output()).contains("event: update");
        verify(capture.onStreamClosed).run();
        assertThat(channel.clientCount()).isEqualTo(1);
    }

    @Test
    void aStreamClosesEvenWhenTheContainerRefusesToCompleteIt() {
        SseCapture capture = openClient();
        doThrow(new IllegalStateException("already dispatched")).when(capture.asyncContext).complete();

        capture.closeHandle.get().close();
        clock.tick();

        verify(capture.onStreamClosed).run();
        assertThat(channel.clientCount()).isZero();
    }

    @Test
    void openAfterTheChannelIsClosedIsRefused() {
        channel.close();
        clock.tick();
        var capture = new SseCapture("host1", 1111);

        Optional<SseChannel.SseStream> refused = getAsUnchecked(
                () -> channel.open(capture.asyncContext, capture.response, capture.onStreamClosed, _ -> {}));

        assertThat(refused).isEmpty();
        assertThat(capture.output()).isEmpty();
    }

    @Test
    void aChannelMustAcceptAtLeastOneClient() {
        assertThatThrownBy(() -> new SseChannel("test", executor, clock, WRITER, alertService, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closeHandleReturnedFromOpenClosesThatStream() {
        SseCapture capture = openClient();
        capture.reset();

        capture.closeHandle.get().close();
        clock.tick();

        channel.broadcast("update", Map.of("value", "v"));
        clock.tick();

        assertThat(capture.output()).doesNotContain("event: update");
        verify(capture.asyncContext).complete();
        verify(capture.onStreamClosed).run();
    }

    @Test
    void closeClosesEveryOpenStream() {
        SseCapture capture1 = openClient("host1", 1111);
        SseCapture capture2 = openClient("host2", 2222);

        channel.close();
        clock.tick();

        verify(capture1.onStreamClosed).run();
        verify(capture2.onStreamClosed).run();
        assertThat(channel.clientCount()).isZero();
    }

    @Test
    void aFrameThatCannotBeSerialisedIsEscalatedRatherThanPassedOffAsALostClient() {
        SseCapture capture = openClient();
        capture.reset();

        channel.broadcast("update", new Unserialisable());
        clock.tick();

        assertThat(alertService.activeAlertsById().values())
                .as("a frame the writer cannot map is a defect in the feature's frame types, not a disconnect")
                .singleElement()
                .satisfies(alert -> assertThat(alert.title()).isEqualTo(SseChannel.SERIALISATION_FAILED_ALERT_TITLE));
        // The frame prefix is already on the wire when the writer fails, so the stream is unrecoverable and the client is dropped.
        verify(capture.onStreamClosed).run();
        assertThat(channel.clientCount()).isZero();
    }

    @Test
    void aClientWhoseCloseCallbackFailsIsStillDroppedFromTheChannel() {
        SseCapture broken = openClient("host1", 1111);
        doThrow(new IllegalStateException("feature teardown blew up")).when(broken.onStreamClosed).run();

        broken.closeHandle.get().close();
        clock.tick();

        assertThat(channel.clientCount()).as("the slot is released even though the callback threw").isZero();

        // Closing runs on the executor while it iterates the connected set, so the throw must not escape to abort that iteration either.
        SseCapture healthy = openClient("host2", 2222);
        healthy.reset();
        channel.broadcast("update", Map.of("value", "v"));
        clock.tick();

        assertThat(broken.output()).doesNotContain("event: update");
        assertThat(healthy.output()).contains("event: update");
    }

    @Test
    void closeHandleAfterChannelCloseIsNoOpAndDoesNotThrow() {
        SseCapture capture = openClient();
        // Close the channel and terminate its executor, mirroring component shutdown while a stream close is still pending. The returned close handle must then
        // be a silent no-op, not a rejected task scheduled onto the terminated executor.
        channel.close();
        clock.tick();
        executor.close();
        clock.tick();

        assertThatCode(() -> capture.closeHandle.get().close()).doesNotThrowAnyException();
    }

    @Test
    void openTaskDrainedAfterChannelCloseClosesTheClientWithoutDeliveringAnImage() {
        var capture = new SseCapture("localhost", 12345);
        var initialImageDelivered = new boolean[]{false};

        // Enqueue the registration task but do not tick, so it stays queued on the executor.
        getAsUnchecked(() -> channel.open(capture.asyncContext, capture.response, capture.onStreamClosed, _ -> initialImageDelivered[0] = true));

        // Only now close the channel and tick, so the queued task drains after teardown. It must skip all work and close the freshly-created client, which
        // close()'s own drain never saw.
        channel.close();
        clock.tick();

        assertThat(initialImageDelivered[0]).isFalse();
        assertThat(capture.output()).isEmpty();
        verify(capture.onStreamClosed).run();
    }

    @Test
    void streamCloseIsIdempotentAcrossMultipleTriggers() {
        SseCapture capture = openClient();

        capture.closeHandle.get().close();
        clock.tick();
        asUnchecked(() -> capture.asyncListener.get().onComplete(null));
        clock.tick();

        verify(capture.asyncContext, times(1)).complete();
        verify(capture.onStreamClosed, times(1)).run();
    }

    @Test
    void openRefusesClientsBeyondTheCapacityCap() {
        channel.close();
        clock.tick();
        channel = createChannel(1);
        clock.tick();

        SseCapture capture1 = openClient("host1", 1111);
        var capture2 = new SseCapture("host2", 2222);
        Optional<SseChannel.SseStream> refused = getAsUnchecked(
                () -> channel.open(capture2.asyncContext, capture2.response, capture2.onStreamClosed, _ -> {}));
        clock.tick();

        assertThat(refused).isEmpty();
        assertThat(capture2.output()).isEmpty();
        verify(capture2.response, never()).setContentType(any());
        assertThat(channel.clientCount()).isEqualTo(1);
        assertThat(capture1.output()).contains("event: hello");
    }

    @Test
    void capacityIsReleasedWhenAStreamCloses() {
        channel.close();
        clock.tick();
        channel = createChannel(1);
        clock.tick();

        SseCapture capture1 = openClient("host1", 1111);
        capture1.closeHandle.get().close();
        clock.tick();

        assertThat(channel.clientCount()).isZero();
        SseCapture capture2 = openClient("host2", 2222);
        assertThat(capture2.output()).contains("event: hello");
    }

    @Test
    void aClientThatFailsToWriteIsDroppedWithoutAffectingTheOthers() {
        SseCapture healthy = openClient("host1", 1111);
        SseCapture broken = openClient("host2", 2222);
        broken.out.failWrites(true);
        healthy.reset();

        channel.broadcast("update", Map.of("value", "v"));
        clock.tick();

        assertThat(healthy.output()).contains("event: update");
        verify(broken.onStreamClosed).run();
        assertThat(channel.clientCount()).isEqualTo(1);

        // Let writes succeed again, so a second broadcast reaching the dropped client would leave visible bytes in its capture.
        broken.out.failWrites(false);
        broken.reset();
        channel.broadcast("update", Map.of("value", "v2"));
        clock.tick();

        assertThat(broken.output()).as("a client dropped mid-broadcast is no longer in the broadcast set").isEmpty();
    }

    @Test
    void anUncommittedResponseIsLeftForTheCallerToAnswerOn(@Mock HttpServletResponse failingResponse) {
        var capture = new SseCapture("host1", 1111);
        when(failingResponse.isCommitted()).thenReturn(false);
        doThrow(new IllegalStateException("headers rejected")).when(failingResponse).setContentType(any());

        assertThatThrownBy(() -> channel.open(capture.asyncContext, failingResponse, capture.onStreamClosed, _ -> {}))
                .isInstanceOf(IllegalStateException.class);
        clock.tick();

        verify(capture.asyncContext, never()).complete();
        assertThat(channel.clientCount()).isZero();
    }

    @Test
    void aCommittedResponseHasItsContextCompletedBeforeTheFailureIsRaised() {
        var capture = new SseCapture("host1", 1111);
        when(capture.response.isCommitted()).thenReturn(true);
        asUnchecked(() -> doThrow(new IllegalStateException("stream gone")).when(capture.response).getOutputStream());

        assertThatThrownBy(() -> channel.open(capture.asyncContext, capture.response, capture.onStreamClosed, _ -> {}))
                .isInstanceOf(IllegalStateException.class);
        clock.tick();

        verify(capture.asyncContext).complete();
        assertThat(channel.clientCount()).isZero();
    }

    @Test
    void aClientWhoseHelloFrameFailsIsForgottenRatherThanWrittenToForever() {
        SseCapture healthy = openClient("host1", 1111);
        var capture = new SseCapture("host2", 2222);
        capture.out.failWrites(true);

        getAsUnchecked(() -> channel.open(capture.asyncContext, capture.response, capture.onStreamClosed, _ -> {}));
        clock.tick();
        healthy.reset();
        // Let writes succeed again, so a broadcast reaching this client would leave visible bytes in the capture.
        capture.out.failWrites(false);

        channel.broadcast("update", Map.of("value", "v"));
        clock.tick();

        assertThat(capture.output()).as("a client dropped during its preamble is no longer in the broadcast set").isEmpty();
        assertThat(healthy.output()).contains("event: update");
        verify(capture.onStreamClosed).run();
        assertThat(channel.clientCount()).isEqualTo(1);
    }

    // region helpers

    private SseChannel createChannel(int maxClients) {
        var newChannel = new SseChannel("test", executor, clock, WRITER, alertService, maxClients);
        newChannel.start();
        return newChannel;
    }

    private SseCapture openClient() {
        return openClient("localhost", 12345);
    }

    private SseCapture openClient(String host, int port) {
        return openClient(host, port, _ -> {});
    }

    private SseCapture openClient(String host, int port, Consumer<? super SseChannel.SseStream> onOpen) {
        var capture = new SseCapture(host, port);
        capture.closeHandle.set(getAsUnchecked(() -> channel.open(capture.asyncContext, capture.response, capture.onStreamClosed, onOpen)).orElseThrow());
        clock.tick();
        return capture;
    }

    // endregion

    // region test infrastructure

    /// A payload Jackson cannot map, standing in for a frame record whose shape the feature got wrong.
    private static final class Unserialisable {
    }

    /// One way the servlet container reports on an async request's life.
    private interface ContainerEvent {
        void deliverTo(AsyncListener listener, AsyncContext asyncContext) throws IOException;
    }

    private static final class SseCapture {
        final MutableReference<AsyncListener> asyncListener = new MutableReference<>();
        final MutableReference<Closeable> closeHandle = new MutableReference<>();
        final Runnable onStreamClosed = mock(Runnable.class);
        final AsyncContext asyncContext = mock(AsyncContext.class);
        final HttpServletResponse response = mock(HttpServletResponse.class);
        final CapturingServletOutputStream out = new CapturingServletOutputStream();

        // Fixture stubs, not per-test expectations: a test that refuses the client at the capacity or closed-channel guard never reads most of them.
        SseCapture(String host, int port) {
            var request = mock(ServletRequest.class);
            lenient().when(request.getRemoteHost()).thenReturn(host);
            lenient().when(request.getRemotePort()).thenReturn(port);
            lenient().when(asyncContext.getRequest()).thenReturn(request);
            lenient().when(asyncContext.getResponse()).thenReturn(response);
            asUnchecked(() -> lenient().when(response.getOutputStream()).thenReturn(out));
            lenient().doAnswer(invocation -> {
                asyncListener.set(invocation.getArgument(0));
                return null;
            }).when(asyncContext).addListener(any(AsyncListener.class));
        }

        String output() {
            return out.output();
        }

        void reset() {
            out.reset();
        }
    }

    // endregion

    private record HelloData(int clientIdSeqNum, Instant serverTime) {}

    private record PingData(Instant serverTime) {}
}
