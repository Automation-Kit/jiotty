package net.yudichev.jiotty.user.ui.sse;

import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.reflect.TypeToken;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.adminalerts.TestAdminAlertService;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.RejectingSchedulingExecutor;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.common.lang.MutableReference;
import net.yudichev.jiotty.common.metrics.GuardMetrics;
import net.yudichev.jiotty.user.ui.io.AsyncExchange;
import net.yudichev.jiotty.user.ui.io.Deadline;
import net.yudichev.jiotty.user.ui.io.testing.FakeAsyncContext;
import net.yudichev.jiotty.user.ui.io.testing.UnserialisablePayload;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.google.common.net.HttpHeaders.RETRY_AFTER;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.rest.ContentTypes.CONTENT_TYPE_EVENT_STREAM;
import static net.yudichev.jiotty.common.rest.HttpStatuses.INTERNAL_SERVER_ERROR_500;
import static net.yudichev.jiotty.common.rest.HttpStatuses.SERVICE_UNAVAILABLE_503;
import static net.yudichev.jiotty.user.ui.sse.testing.SseFrames.allDataOf;
import static net.yudichev.jiotty.user.ui.sse.testing.SseFrames.dataOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// Transport-level behaviour of the shared SSE channel: headers, wire format, hello and heartbeat frames, per-client and broadcast delivery, frames
/// superseding each other for a slow reader, the client and budget caps, and the close paths. What is streamed over a channel is covered by each feature's own
/// test.
@ExtendWith(MockitoExtension.class)
class SseChannelTest {
    /// Rooted at [Object], as every channel's writer is, so each frame is serialised from its own type.
    private static final ObjectWriter WRITER = Json.createWriterFor(new TypeToken<>() {});

    private final TestAdminAlertService alertService = new TestAdminAlertService();
    private final List<SseCapture> captures = new ArrayList<>();
    private ProgrammableClock clock;
    private RejectingSchedulingExecutor executor;
    private SseChannel channel;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        executor = new RejectingSchedulingExecutor(clock.createSingleThreadedSchedulingExecutor("test"));
        channel = createChannel(SseChannel.UNBOUNDED);
        clock.tick();
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.close();
            clock.tick();
        }
        for (SseCapture capture : captures) {
            assertThat(capture.out.leakedBuffers()).as("pooled buffers given back once every exchange has ended").isEmpty();
        }
    }

    @Test
    void openWritesEventStreamHeaders() {
        SseCapture capture = openClient();

        verify(capture.response).setContentType(CONTENT_TYPE_EVENT_STREAM);
        verify(capture.response).setCharacterEncoding("utf-8");
        verify(capture.response).setHeader("Cache-Control", "no-cache");
        verify(capture.response).setHeader("Connection", "keep-alive");
        verify(capture.response).setHeader("X-Accel-Buffering", "no");
        verify(capture.response).setHeader("X-Client-Id-Seq-Num", "1");
        assertThat(capture.asyncContext.getTimeout()).isZero();
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

        assertThat(capture.output()).contains("event: later").contains("42");
    }

    @Test
    void broadcastReachesEveryConnectedClient() {
        SseCapture capture1 = openClient("host1", 1111);
        SseCapture capture2 = openClient("host2", 2222);
        capture1.reset();
        capture2.reset();

        channel.broadcastSink().send("update", Map.of("value", "v"));
        clock.tick();

        assertThat(capture1.output()).contains("event: update");
        assertThat(capture2.output()).contains("event: update");
    }

    /// Unsuperseded frames reach a slow client one by one, however many share their event name, as a list of removals must.
    @Test
    void anUnsupersededBroadcastReachesASlowClientWhole() {
        SseCapture capture = openClient("host1", 1111);
        capture.reset();
        capture.out.stallReading();
        channel.broadcastSink().send("held", Map.of("value", "in flight"));

        channel.broadcastSink().sendUnsuperseded("ticket-removed", Map.of("ids", "a"));
        channel.broadcastSink().sendUnsuperseded("ticket-removed", Map.of("ids", "b"));
        clock.tick();
        capture.out.resumeReading();

        assertThat(allDataOf(capture.output(), "ticket-removed")).containsExactly("{\"ids\":\"a\"}", "{\"ids\":\"b\"}");
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

    /// A client reading slower than frames arrive receives only the newest frame for each key, at that frame's own position in the queue, so an update sent
    /// after a snapshot follows that snapshot.
    @Test
    void framesWaitingForASlowReaderAreSupersededByLaterOnesWithTheSameKey() {
        var sinkRef = new MutableReference<SseChannel.SseStream>();
        SseCapture capture = openClient("host1", 1111, sinkRef::set);
        capture.reset();
        capture.out.stallReading();
        SseChannel.SseStream stream = sinkRef.get();
        stream.send("held", Map.of("value", "in flight"));

        stream.send("user-update", "user:1", Map.of("version", 1));
        stream.send("users-snapshot", Map.of("users", "all"));
        stream.send("user-update", "user:1", Map.of("version", 2));
        stream.sendUnsuperseded("ticket-removed", Map.of("ids", "a"));
        stream.sendUnsuperseded("ticket-removed", Map.of("ids", "b"));
        capture.out.resumeReading();

        String output = capture.output();
        assertThat(allDataOf(output, "user-update")).containsExactly("{\"version\":2}");
        assertThat(allDataOf(output, "ticket-removed")).containsExactly("{\"ids\":\"a\"}", "{\"ids\":\"b\"}");
        assertThat(output.indexOf("event: users-snapshot")).isLessThan(output.indexOf("event: user-update"));
    }

    /// Frames keep arriving for a dropped client until its removal runs on the channel executor; each one overflows the client's queue again, and none
    /// increments the drop counter again.
    @Test
    void aClientThatFallsFullyBehindIsDroppedAndCountedOnceAsASlowReader() {
        var meterRegistry = new SimpleMeterRegistry();
        StalledClient client = openClientHoldingAWrite(meterRegistry);

        for (int i = 0; i <= PendingFrames.CAPACITY + 1; i++) {
            client.stream().sendUnsuperseded("update", i);
        }
        clock.tick();

        verify(client.capture().onStreamClosed).run();
        assertThat(client.capture().asyncContext.isCompleted()).isTrue();
        assertThat(channel.clientCount()).isZero();
        assertThat(meterRegistry.counter(SseAdmissionImpl.DROPPED_COUNTER).count()).isEqualTo(1);
    }

    /// A held write that times out is a client that stopped reading; one that fails otherwise is a client that went away, which is no slow reader.
    @ParameterizedTest
    @MethodSource
    void aClientWhoseHeldWriteFailsIsDroppedAndCountedAsASlowReaderOnlyOnATimeout(Throwable writeFailure, double expectedDropCount) {
        var meterRegistry = new SimpleMeterRegistry();
        StalledClient client = openClientHoldingAWrite(meterRegistry);

        client.capture().out.failHeldWrite(writeFailure);
        clock.tick();

        verify(client.capture().onStreamClosed).run();
        assertThat(channel.clientCount()).isZero();
        assertThat(meterRegistry.counter(SseAdmissionImpl.DROPPED_COUNTER).count()).isEqualTo(expectedDropCount);
    }

    static Stream<Arguments> aClientWhoseHeldWriteFailsIsDroppedAndCountedAsASlowReaderOnlyOnATimeout() {
        return Stream.of(arguments(named("idle timeout", new TimeoutException("Idle timeout expired")), 1.0),
                         arguments(named("connection reset", new IOException("connection reset")), 0.0));
    }

    @ParameterizedTest
    @MethodSource
    void everyContainerEndOfLifeEventStopsDelivery(ContainerEvent event) {
        SseCapture capture = openClient();
        capture.reset();

        event.deliverTo(capture.asyncContext);
        clock.tick();

        channel.broadcastSink().send("update", Map.of("value", "v"));
        clock.tick();

        assertThat(capture.output()).doesNotContain("event: update");
        assertThat(channel.clientCount()).isZero();
        verify(capture.onStreamClosed).run();
    }

    static Stream<Arguments> everyContainerEndOfLifeEventStopsDelivery() {
        return Stream.of(arguments(named("abort", (ContainerEvent) FakeAsyncContext::abort)),
                         arguments(named("completion", (ContainerEvent) FakeAsyncContext::complete)));
    }

    /// When a client has gone before its headers went out, the channel, which holds the exchange's claim, completes the exchange and gives the client's slots
    /// back.
    @Test
    void aClientWhoseHeadersCannotBeWrittenGivesItsCapacitySlotBack() {
        SseCapture capture = newCapture("host1", 1111);
        asUnchecked(() -> doThrow(new IOException("gone")).when(capture.response).flushBuffer());

        assertThat(channel.open(exchangeOf(capture), capture.onStreamClosed, _ -> {})).isPresent();
        clock.tick();

        assertThat(channel.clientCount()).isZero();
        assertThat(capture.asyncContext.isCompleted()).isTrue();
        verify(capture.onStreamClosed).run();
        assertThat(alertService.activeAlertsById()).isEmpty();
    }

    /// An exchange answered before the stream opens has nothing left to stream to, so the stream is refused and its slots go back.
    @Test
    void aStreamOnAnExchangeClaimedFirstIsRefusedAndGivesItsSlotsBack() {
        SseCapture capture = newCapture("host1", 1111);
        AsyncExchange exchange = exchangeOf(capture);
        assertThat(exchange.claim()).isPresent();

        assertThat(channel.open(exchange, capture.onStreamClosed, _ -> {})).isEmpty();

        assertThat(channel.clientCount()).isZero();
        verify(capture.response, never()).setContentType(any());
    }

    @Test
    void aClientWhoseRegistrationCannotBeScheduledGivesItsCapacitySlotBack(@Mock SchedulingExecutor rejectingExecutor) {
        // A real SchedulingExecutor rejects work once its queue is full or it has shut down; the deterministic one accepts everything, so stand one in.
        when(rejectingExecutor.scheduleAtFixedRate(any(), any())).thenReturn(Closeable.noop());
        doThrow(new RejectedExecutionException("queue full")).when(rejectingExecutor).execute(any(), any());
        var rejectingChannel = new SseChannel("rejecting", rejectingExecutor, clock, WRITER, alertService, SseAdmission.unlimited(), 1);
        rejectingChannel.start();
        SseCapture capture = newCapture("host1", 1111);

        assertThatThrownBy(() -> rejectingChannel.open(exchangeOf(capture), capture.onStreamClosed, _ -> {}))
                .isInstanceOf(RejectedExecutionException.class);

        assertThat(rejectingChannel.clientCount()).isZero();
        verify(capture.onStreamClosed).run();
    }

    /// A stream closed before its open task runs, while the executor rejects the removal task that follows, has its `onStreamClosed` run once that open
    /// task runs.
    @Test
    void aStreamThatEndsBeforeRegistrationRunsOnStreamClosedWhenItsRemovalIsRejected(@Mock SchedulingExecutor queueFullExecutor) {
        when(queueFullExecutor.scheduleAtFixedRate(any(), any())).thenReturn(Closeable.noop());
        var registration = new MutableReference<Runnable>();
        doAnswer(invocation -> {
            registration.set(invocation.getArgument(1));
            return null;
        }).when(queueFullExecutor).execute(any(), any());
        when(queueFullExecutor.tryExecute(any(), any())).thenThrow(new RejectedExecutionException("queue full"));
        var queueFullChannel = new SseChannel("queue-full", queueFullExecutor, clock, WRITER, alertService, SseAdmission.unlimited(), 1);
        queueFullChannel.start();
        SseCapture capture = newCapture("host1", 1111);
        SseChannel.SseStream stream = queueFullChannel.open(exchangeOf(capture), capture.onStreamClosed, _ -> {}).orElseThrow();

        stream.close();
        registration.get().run();

        verify(capture.onStreamClosed).run();
    }

    /// An exchange the container aborted before the stream opened, as when the client went away, explains the failure to open, so it raises no alert.
    @Test
    void aClientWhoseExchangeEndedBeforeItOpenedIsDroppedWithoutAnAlert() {
        SseCapture capture = newCapture("host1", 1111);
        AsyncExchange exchange = exchangeOf(capture);
        capture.asyncContext.abort();

        channel.open(exchange, capture.onStreamClosed, _ -> {});
        clock.tick();

        verify(capture.onStreamClosed).run();
        assertThat(channel.clientCount()).isZero();
        assertThat(alertService.activeAlertsById()).isEmpty();
    }

    @Test
    void aClientWhoseInitialImageThrowsIsDropped() {
        SseCapture healthy = openClient("host1", 1111);
        SseCapture capture = newCapture("host2", 2222);

        channel.open(exchangeOf(capture), capture.onStreamClosed, _ -> {throw new IllegalStateException("feature blew up");});
        clock.tick();
        capture.reset();
        healthy.reset();

        channel.broadcastSink().send("update", Map.of("value", "v"));
        clock.tick();

        assertThat(capture.output()).as("a client whose initial image failed is no longer in the broadcast set").isEmpty();
        assertThat(healthy.output()).contains("event: update");
        verify(capture.onStreamClosed).run();
        assertThat(channel.clientCount()).isEqualTo(1);
    }

    @Test
    void aStreamClosesEvenWhenTheContainerRefusesToCompleteIt() {
        SseCapture capture = openClient();
        capture.asyncContext.refuseCompletion();

        capture.closeHandle.get().close();
        clock.tick();

        verify(capture.onStreamClosed).run();
        assertThat(channel.clientCount()).isZero();
    }

    @Test
    void openAfterTheChannelIsClosedIsRefused() {
        channel.close();
        clock.tick();
        SseCapture capture = newCapture("host1", 1111);

        Optional<SseChannel.SseStream> refusedStream = channel.open(exchangeOf(capture), capture.onStreamClosed, _ -> {});

        assertThat(refusedStream).isEmpty();
        assertThat(capture.output()).isEmpty();
    }

    @Test
    void startStreamStartsTheRequestsAsyncContextAndOpensAStreamOnIt(@Mock HttpServletRequest request) {
        SseCapture capture = newCapture("host1", 1111);
        when(request.startAsync()).thenReturn(capture.asyncContext);

        Closeable stream = channel.startStream(request, capture.response, capture.onStreamClosed, sink -> sink.send("image", 1));
        clock.tick();

        assertThat(capture.output()).contains("event: hello").contains("event: image");
        stream.close();
        clock.tick();
        verify(capture.onStreamClosed).run();
    }

    @Test
    void startStreamOnAClosedChannelAnswersUnavailableAndReportsTheStreamClosed(@Mock HttpServletRequest request) {
        channel.close();
        clock.tick();
        SseCapture capture = newCapture("host1", 1111);
        when(request.startAsync()).thenReturn(capture.asyncContext);

        Closeable refusedStream = channel.startStream(request, capture.response, capture.onStreamClosed, _ -> {});

        assertRefusal(capture, SseChannel.CLOSED_ERROR, SseChannel.CLOSED_RETRY_AFTER);
        verify(capture.onStreamClosed).run();
        // The refused stream has nothing left to end, so closing it completes nothing and reports nothing a second time.
        assertThatCode(refusedStream::close).doesNotThrowAnyException();
        clock.tick();
        assertThat(capture.asyncContext.completions()).isEqualTo(1);
        verify(capture.onStreamClosed, times(1)).run();
    }

    @Test
    void startStreamBeyondTheChannelsCapAnswersThatTheUserIsAtCapacity(@Mock HttpServletRequest request) {
        var meterRegistry = new SimpleMeterRegistry();
        recreateChannel(1, new SseAdmissionImpl(10, meterRegistry));
        openClient("host1", 1111);
        SseCapture capture = newCapture("host2", 2222);
        when(request.startAsync()).thenReturn(capture.asyncContext);

        channel.startStream(request, capture.response, capture.onStreamClosed, _ -> {});

        assertRefusal(capture, SseChannel.CHANNEL_FULL_ERROR, SseChannel.CHANNEL_FULL_RETRY_AFTER);
        verify(capture.onStreamClosed).run();
        assertThat(meterRegistry.counter(GuardMetrics.REJECTED_COUNTER,
                                         GuardMetrics.TAG_GUARD,
                                         SseAdmissionImpl.GUARD,
                                         GuardMetrics.TAG_OUTCOME,
                                         GuardMetrics.OUTCOME_USER_AT_CAPACITY).count()).isEqualTo(1);
    }

    /// A client that went away holds its slot until the second heartbeat write after it left, so a retry sooner than two heartbeats can be refused again.
    @Test
    void aClientRefusedForAFullChannelRetriesAfterTwoHeartbeats() {
        assertThat(SseChannel.CHANNEL_FULL_RETRY_AFTER).isGreaterThan(SseChannel.HEARTBEAT_PERIOD.multipliedBy(2));
    }

    @Test
    void startStreamBeyondTheSharedBudgetAnswersThatTheServerIsAtCapacityAndGivesTheChannelSlotBack(@Mock HttpServletRequest request) {
        var admission = new SseAdmissionImpl(1, new SimpleMeterRegistry());
        SseChannel otherChannel = started(new SseChannel("other", executor, clock, WRITER, alertService, admission, 5));
        recreateChannel(5, admission);
        SseCapture otherCapture = newCapture("host1", 1111);
        otherChannel.open(exchangeOf(otherCapture), otherCapture.onStreamClosed, _ -> {});
        clock.tick();
        SseCapture capture = newCapture("host2", 2222);
        when(request.startAsync()).thenReturn(capture.asyncContext);

        channel.startStream(request, capture.response, capture.onStreamClosed, _ -> {});

        assertRefusal(capture, SseChannel.AT_CAPACITY_ERROR, SseChannel.AT_CAPACITY_RETRY_AFTER);
        assertThat(channel.clientCount()).isZero();
        otherChannel.close();
        clock.tick();
    }

    @Test
    void aStreamGivesItsPlaceInTheSharedBudgetBackWhenItEnds() {
        var meterRegistry = new SimpleMeterRegistry();
        recreateChannel(5, new SseAdmissionImpl(10, meterRegistry));
        SseCapture capture = openClient();
        assertThat(meterRegistry.get(SseAdmissionImpl.ACTIVE_CLIENTS_GAUGE).gauge().value()).isEqualTo(1);

        capture.closeHandle.get().close();
        clock.tick();

        assertThat(meterRegistry.get(SseAdmissionImpl.ACTIVE_CLIENTS_GAUGE).gauge().value()).isZero();
    }

    @Test
    void aChannelMustAcceptAtLeastOneClient() {
        assertThatThrownBy(() -> new SseChannel("test", executor, clock, WRITER, alertService, SseAdmission.unlimited(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closeHandleReturnedFromOpenClosesThatStream() {
        SseCapture capture = openClient();
        capture.reset();

        capture.closeHandle.get().close();
        clock.tick();

        channel.broadcastSink().send("update", Map.of("value", "v"));
        clock.tick();

        assertThat(capture.output()).doesNotContain("event: update");
        assertThat(capture.asyncContext.isCompleted()).isTrue();
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

        channel.broadcastSink().send("update", UnserialisablePayload.create());
        clock.tick();

        assertThat(alertService.activeAlertsById().values())
                .as("a frame the writer cannot map is a defect in the feature's frame types, not a disconnect")
                .singleElement()
                .satisfies(alert -> assertThat(alert.title()).isEqualTo(SseChannel.SERIALISATION_FAILED_ALERT_TITLE));
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

        SseCapture healthy = openClient("host2", 2222);
        healthy.reset();
        channel.broadcastSink().send("update", Map.of("value", "v"));
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
        SseCapture capture = newCapture("localhost", 12345);
        var initialImageDelivered = new boolean[]{false};

        // Enqueue the registration task but do not tick, so it stays queued on the executor.
        channel.open(exchangeOf(capture), capture.onStreamClosed, _ -> initialImageDelivered[0] = true);

        // Only now close the channel and tick, so the queued task drains after teardown. It must skip all work and close the freshly-created client, which
        // close()'s own drain never saw.
        channel.close();
        clock.tick();

        assertThat(initialImageDelivered[0]).isFalse();
        verify(capture.onStreamClosed).run();
    }

    @Test
    void streamCloseIsIdempotentAcrossMultipleTriggers() {
        SseCapture capture = openClient();

        capture.asyncContext.abort();
        clock.tick();
        capture.closeHandle.get().close();
        clock.tick();

        assertThat(capture.asyncContext.completions()).as("an aborted exchange is not completed").isZero();
        verify(capture.onStreamClosed, times(1)).run();
    }

    @Test
    void openRefusesClientsBeyondTheCapacityCap() {
        recreateChannel(1, SseAdmission.unlimited());

        SseCapture capture1 = openClient("host1", 1111);
        SseCapture capture2 = newCapture("host2", 2222);
        Optional<SseChannel.SseStream> refusedStream = channel.open(exchangeOf(capture2), capture2.onStreamClosed, _ -> {});
        clock.tick();

        assertThat(refusedStream).isEmpty();
        assertThat(capture2.output()).isEmpty();
        verify(capture2.response, never()).setContentType(any());
        assertThat(channel.clientCount()).isEqualTo(1);
        assertThat(capture1.output()).contains("event: hello");
    }

    @Test
    void capacityIsReleasedWhenAStreamCloses() {
        recreateChannel(1, SseAdmission.unlimited());

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

        channel.broadcastSink().send("update", Map.of("value", "v"));
        clock.tick();

        assertThat(healthy.output()).contains("event: update");
        verify(broken.onStreamClosed).run();
        assertThat(channel.clientCount()).isEqualTo(1);

        // Let writes succeed again, so a second broadcast reaching the dropped client would leave visible bytes in its capture.
        broken.out.failWrites(false);
        broken.reset();
        channel.broadcastSink().send("update", Map.of("value", "v2"));
        clock.tick();

        assertThat(broken.output()).as("a client dropped mid-broadcast is no longer in the broadcast set").isEmpty();
    }

    /// The channel holds the exchange's claim once it opens a stream, so a stream that fails to open before its headers went out is answered with a 500 by
    /// the channel, and alerted.
    @Test
    void anUncommittedStreamThatFailsToOpenIsAnsweredByTheChannel() {
        SseCapture capture = newCapture("host1", 1111);
        doThrow(new IllegalStateException("headers rejected")).when(capture.response).setContentType(CONTENT_TYPE_EVENT_STREAM);

        assertThat(channel.open(exchangeOf(capture), capture.onStreamClosed, _ -> {})).isPresent();
        clock.tick();

        verify(capture.response).setStatus(INTERNAL_SERVER_ERROR_500);
        assertThat(capture.asyncContext.isCompleted()).isTrue();
        assertThat(channel.clientCount()).isZero();
        verify(capture.onStreamClosed).run();
        assertThat(alertService.activeAlertsById().values())
                .singleElement()
                .satisfies(alert -> assertThat(alert.title()).isEqualTo(SseChannel.OPEN_FAILED_ALERT_TITLE));
    }

    /// Once the headers are on the wire, completing is all that is left of an answer.
    @Test
    void aCommittedStreamThatFailsToOpenIsCompletedByTheChannel() {
        SseCapture capture = newCapture("host1", 1111);
        when(capture.response.isCommitted()).thenReturn(true);
        asUnchecked(() -> doThrow(new IllegalStateException("stream gone")).when(capture.response).getOutputStream());

        assertThat(channel.open(exchangeOf(capture), capture.onStreamClosed, _ -> {})).isPresent();
        clock.tick();

        verify(capture.response, never()).setStatus(INTERNAL_SERVER_ERROR_500);
        assertThat(capture.asyncContext.isCompleted()).isTrue();
        assertThat(channel.clientCount()).isZero();
        verify(capture.onStreamClosed).run();
    }

    /// While the channel executor rejects every task, a closed stream completes and frees its slot at once, and its `onStreamClosed` runs by the next
    /// heartbeat, which is scheduled and not queued.
    @Test
    void aStreamWhoseRemovalTheExecutorRejectsIsRemovedByTheNextHeartbeat() {
        SseCapture capture = openClient();
        executor.fillQueue();

        capture.closeHandle.get().close();
        assertThat(capture.asyncContext.isCompleted()).isTrue();
        assertThat(channel.clientCount()).isZero();
        verify(capture.onStreamClosed, never()).run();

        clock.advanceTimeAndTick(SseChannel.HEARTBEAT_PERIOD);
        executor.emptyQueue();

        verify(capture.onStreamClosed).run();
    }

    @Test
    void aClientWhoseHelloFrameFailsIsForgottenRatherThanWrittenToForever() {
        SseCapture healthy = openClient("host1", 1111);
        SseCapture capture = newCapture("host2", 2222);
        capture.out.failWrites(true);

        channel.open(exchangeOf(capture), capture.onStreamClosed, _ -> {});
        clock.tick();
        healthy.reset();
        // Let writes succeed again, so a broadcast reaching this client would leave visible bytes in the capture.
        capture.out.failWrites(false);

        channel.broadcastSink().send("update", Map.of("value", "v"));
        clock.tick();

        assertThat(capture.output()).as("a client dropped during its preamble is no longer in the broadcast set").isEmpty();
        assertThat(healthy.output()).contains("event: update");
        verify(capture.onStreamClosed).run();
        assertThat(channel.clientCount()).isEqualTo(1);
    }

    // region helpers

    private SseChannel createChannel(int maxClients) {
        return started(new SseChannel("test", executor, clock, WRITER, alertService, SseAdmission.unlimited(), maxClients));
    }

    private void recreateChannel(int maxClients, SseAdmission admission) {
        channel.close();
        clock.tick();
        channel = started(new SseChannel("test", executor, clock, WRITER, alertService, admission, maxClients));
        clock.tick();
    }

    private static SseChannel started(SseChannel newChannel) {
        newChannel.start();
        return newChannel;
    }

    /// Recreates the channel against a budget publishing to `meterRegistry`, and opens a client whose one write is held unread.
    private StalledClient openClientHoldingAWrite(SimpleMeterRegistry meterRegistry) {
        recreateChannel(SseChannel.UNBOUNDED, new SseAdmissionImpl(10, meterRegistry));
        var streamRef = new MutableReference<SseChannel.SseStream>();
        SseCapture capture = openClient("host1", 1111, streamRef::set);
        capture.out.stallReading();
        streamRef.get().send("held", 0);
        return new StalledClient(capture, streamRef.get());
    }

    private SseCapture openClient() {
        return openClient("localhost", 12345);
    }

    private SseCapture openClient(String host, int port) {
        return openClient(host, port, _ -> {});
    }

    private SseCapture openClient(String host, int port, Consumer<? super SseChannel.SseStream> onOpen) {
        SseCapture capture = newCapture(host, port);
        capture.closeHandle.set(channel.open(exchangeOf(capture), capture.onStreamClosed, onOpen).orElseThrow());
        clock.tick();
        return capture;
    }

    /// Starts the exchange of `capture`'s request, as the servlet opening its stream does.
    private AsyncExchange exchangeOf(SseCapture capture) {
        return AsyncExchange.start(alertService, capture.request, capture.response, Deadline.none());
    }

    private SseCapture newCapture(String host, int port) {
        var capture = new SseCapture(host, port);
        captures.add(capture);
        return capture;
    }

    private static void assertRefusal(SseCapture capture, String error, Duration retryAfter) {
        verify(capture.response).setStatus(SERVICE_UNAVAILABLE_503);
        verify(capture.response).setHeader(RETRY_AFTER, String.valueOf(retryAfter.toSeconds()));
        assertThat(capture.out.errorCode()).isEqualTo(error);
        assertThat(capture.asyncContext.isCompleted()).isTrue();
    }

    // endregion

    // region test infrastructure

    private record StalledClient(SseCapture capture, SseChannel.SseStream stream) {}

    /// One way the servlet container reports on an async request's life.
    private interface ContainerEvent {
        void deliverTo(FakeAsyncContext asyncContext);
    }

    private static final class SseCapture {
        final MutableReference<Closeable> closeHandle = new MutableReference<>();
        final Runnable onStreamClosed = mock(Runnable.class);
        final HttpServletResponse response = mock(HttpServletResponse.class);
        final CapturingServletOutputStream out = new CapturingServletOutputStream();
        final FakeAsyncContext asyncContext;

        final HttpServletRequest request = mock(HttpServletRequest.class);

        // Fixture stubs, not per-test expectations: a test that refuses the client at the capacity or closed-channel guard never reads most of them.
        SseCapture(String host, int port) {
            lenient().when(request.getRemoteHost()).thenReturn(host);
            lenient().when(request.getRemotePort()).thenReturn(port);
            asUnchecked(() -> lenient().when(response.getOutputStream()).thenReturn(out));
            asyncContext = new FakeAsyncContext(request, response);
            lenient().when(request.startAsync()).thenReturn(asyncContext);
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
