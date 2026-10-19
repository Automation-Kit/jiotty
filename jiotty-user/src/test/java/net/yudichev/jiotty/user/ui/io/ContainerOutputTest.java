package net.yudichev.jiotty.user.ui.io;

import com.google.common.collect.ImmutableList;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.adminalerts.TestAdminAlertService;
import net.yudichev.jiotty.user.ui.io.testing.FakeAsyncContext;
import net.yudichev.jiotty.user.ui.io.testing.UnserialisablePayload;
import net.yudichev.jiotty.user.ui.sse.testing.CapturingServletOutputStream;
import org.eclipse.jetty.io.ByteBufferPool;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.rest.HttpStatuses.INTERNAL_SERVER_ERROR_500;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContainerOutputTest {
    private final CapturingServletOutputStream stream = new CapturingServletOutputStream();
    private final TestSource source = new TestSource();
    private final List<EndReason> endReasons = new ArrayList<>();
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    private FakeAsyncContext asyncContext;
    private AsyncExchange.Claim claim;
    private ContainerOutput output;

    @BeforeEach
    void setUp() {
        asUnchecked(() -> lenient().when(response.getOutputStream()).thenReturn(stream));
        asyncContext = new FakeAsyncContext(request, response);
        when(request.startAsync()).thenReturn(asyncContext);
        claim = AsyncExchange.start(new TestAdminAlertService(), request, response, Deadline.none()).claim().orElseThrow();
        output = new ContainerOutput(claim, stream, source, endReasons::add);
    }

    @AfterEach
    void tearDown() {
        assertThat(stream.leakedBuffers()).as("every pooled chunk returned to the pool").isEmpty();
    }

    @Test
    void writesTheBodiesReadyAtStartInOrderAndCompletesOnceTheSourceFinishes() {
        source.offer("first");
        source.offer("second");
        source.finish();

        output.start();

        assertThat(stream.output()).isEqualTo("firstsecond");
        assertThat(asyncContext.completions()).isEqualTo(1);
        assertThat(endReasons).containsExactly(EndReason.Finished.INSTANCE);
    }

    @Test
    void writesABodyOfferedWhileIdle() {
        output.start();

        source.offer("later");
        output.sourceReady();

        assertThat(stream.output()).isEqualTo("later");
        assertThat(asyncContext.isCompleted()).isFalse();
        assertThat(endReasons).isEmpty();
        output.close();
    }

    /// Bodies offered while a write is held go out once the client reads again, after the held one and in the order offered.
    @Test
    void bodiesOfferedWhileAWriteIsHeldFollowItOnceTheClientReads() {
        output.start();
        stream.stallReading();
        source.offer("held");
        output.sourceReady();

        source.offer("next");
        output.sourceReady();
        source.offer("last");
        output.sourceReady();
        assertThat(stream.output()).isEmpty();

        stream.resumeReading();

        assertThat(stream.output()).isEqualTo("heldnextlast");
        output.close();
    }

    /// The container invokes [WriteListener#onWritePossible()] again while a write the client has not read is still pending; that write is not repeated,
    /// and later bodies follow it once the client reads.
    @Test
    void aRegistrationCallArrivingWhileAWriteIsHeldLeavesTheWriteInFlight() {
        output.start();
        stream.stallReading();
        source.offer("held");
        output.sourceReady();
        source.offer("next");
        output.sourceReady();

        stream.callBackWhileAWriteIsHeld();

        assertThat(stream.output()).isEmpty();
        assertThat(endReasons).isEmpty();
        stream.resumeReading();
        assertThat(stream.output()).isEqualTo("heldnext");
        output.close();
    }

    @Test
    void writesABodyLargerThanOneChunkWhole() {
        String body = "x".repeat(PooledChunkOutputStream.CHUNK_SIZE * 2 + 17);
        source.offer(body);
        source.finish();

        output.start();

        assertThat(stream.output()).isEqualTo(body);
    }

    @Test
    void closingWhileIdleCompletesTheExchangeOnce() {
        output.start();

        output.close();
        output.close();

        assertThat(asyncContext.completions()).isEqualTo(1);
        assertThat(endReasons).containsExactly(EndReason.Closed.INSTANCE);
    }

    /// Completing the exchange is what makes the container drop the held write; its chunk returns to the pool once the container has finished with the
    /// exchange.
    @Test
    void closingWhileAWriteIsHeldEndsTheExchangeAtOnce() {
        output.start();
        stream.stallReading();
        source.offer("held");
        output.sourceReady();

        output.close();

        assertThat(asyncContext.isCompleted()).isTrue();
        assertThat(endReasons).containsExactly(EndReason.Closed.INSTANCE);
    }

    @Test
    void aBodyOfferedAfterTheExchangeEndedIsNotWritten() {
        output.start();
        output.close();

        source.offer("too late");
        output.sourceReady();

        assertThat(stream.output()).isEmpty();
        assertThat(asyncContext.completions()).isEqualTo(1);
    }

    @Test
    void aHeldWriteThatFailsEndsTheExchangeWithItsCause() {
        output.start();
        stream.stallReading();
        source.offer("held");
        output.sourceReady();
        var timeout = new TimeoutException("Idle timeout expired");

        stream.failHeldWrite(timeout);

        assertThat(asyncContext.isCompleted()).isTrue();
        assertThat(endReasons).singleElement().isEqualTo(new EndReason.Failed(timeout));
    }

    @Test
    void aWriteThatFailsEndsTheExchange() {
        output.start();
        stream.failWrites(true);

        source.offer("unwritable");
        output.sourceReady();

        assertThat(asyncContext.isCompleted()).isTrue();
        assertThat(endReasons).singleElement().isInstanceOfSatisfying(EndReason.Failed.class,
                                                                      failed -> assertThat(failed.cause()).isInstanceOf(IOException.class));
    }

    @Test
    void anUnencodableBodyAnswersABare500AndReportsTheEncodingFailure() {
        source.offer(UnserialisablePayload.createJsonBody());
        source.finish();

        output.start();

        verify(response).reset();
        verify(response).setStatus(INTERNAL_SERVER_ERROR_500);
        assertThat(asyncContext.isCompleted()).isTrue();
        assertThat(endReasons).singleElement().isInstanceOf(EndReason.EncodingFailed.class);
    }

    /// A body whose encoder throws an unchecked exception is a defective body too: its chunks return to the pool, and the exchange ends as an encoding
    /// failure.
    @Test
    void aBodyWhoseEncoderThrowsAnUncheckedExceptionAnswersABare500AndReportsTheEncodingFailure() {
        var failure = new IllegalStateException("defective body");
        source.offer(new ResponseBody() {
            @Override
            public void applyHeadersTo(HttpServletResponse response) {
            }

            @Override
            public void writeTo(OutputStream outputStream) throws IOException {
                outputStream.write("partial".getBytes(UTF_8));
                throw failure;
            }
        });
        source.finish();

        output.start();

        verify(response).setStatus(INTERNAL_SERVER_ERROR_500);
        assertThat(stream.output()).isEmpty();
        assertThat(endReasons).containsExactly(new EndReason.EncodingFailed(failure));
    }

    @Test
    void anExchangeTheContainerCompletesEndsTheOutputOnce() {
        output.start();

        asyncContext.complete();
        output.close();

        assertThat(endReasons).singleElement().isInstanceOf(EndReason.Failed.class);
    }

    /// The container aborts an exchange whose client has gone; its end ends the output, which then has nothing to complete.
    @Test
    void anExchangeTheContainerAbortsEndsTheOutput() {
        output.start();

        asyncContext.abort();

        assertThat(asyncContext.isCompleted()).isFalse();
        assertThat(endReasons).singleElement().isInstanceOf(EndReason.Failed.class);
    }

    /// A held write may still be sending its chunk when the exchange is completed, so the chunk returns to the pool only once the container has finished
    /// with the exchange.
    @Test
    void anAbandonedWritesChunkReturnsToThePoolOnlyOnceTheContainerHasFinished() {
        asyncContext.holdContainerCompletion();
        output.start();
        stream.stallReading();
        source.offer("held");
        output.sourceReady();

        output.close();

        assertThat(asyncContext.isCompleted()).isTrue();
        assertThat(stream.leakedBuffers()).as("the chunk the held write was given").isNotEmpty();
        asyncContext.releaseContainerCompletion();
        assertThat(stream.leakedBuffers()).isEmpty();
    }

    /// A drain the container accepted and then dropped, because the exchange ended first, does not hold the output open: the end ends it.
    @Test
    void aDrainTheContainerDropsLeavesTheOutputToTheExchangesEnd() {
        output.start();
        asyncContext.holdStartedTasks();
        source.offer("never written");
        output.sourceReady();

        asyncContext.abort();
        asyncContext.dropHeldTasks();

        assertThat(stream.output()).isEmpty();
        assertThat(endReasons).singleElement().isInstanceOf(EndReason.Failed.class);
    }

    @Test
    void aQueuedDrainRunningAfterTheOutputClosedWritesNothing() {
        output.start();
        asyncContext.holdStartedTasks();
        source.offer("never written");
        output.sourceReady();
        output.close();

        asyncContext.runHeldTasks();

        assertThat(stream.output()).isEmpty();
        assertThat(asyncContext.completions()).isEqualTo(1);
        assertThat(endReasons).containsExactly(EndReason.Closed.INSTANCE);
    }

    /// A write-possible callback arriving before a queued drain runs drains in its place; the queued drain then finds the stream taken.
    @Test
    void aCallBackArrivingBeforeAQueuedDrainDrainsInItsPlace() {
        HookedOutput hookedOutput = startHookedOutput();
        asyncContext.holdStartedTasks();
        source.offer("written once");
        hookedOutput.output().sourceReady();

        hookedOutput.stream().callBack();
        asyncContext.runHeldTasks();

        assertThat(stream.output()).isEqualTo("written once");
        hookedOutput.output().close();
    }

    /// A container whose thread pool has stopped rejects the drain, so the output ends at once.
    @Test
    void aDrainTheContainerRejectsEndsTheOutput() {
        output.start();
        asyncContext.failStartsWith(new RejectedExecutionException("stopped"));
        source.offer("never written");

        output.sourceReady();

        assertThat(stream.output()).isEmpty();
        assertThat(endReasons).singleElement().isInstanceOf(EndReason.Failed.class);
    }

    /// An encoding failure is a defect in the body, and the only end reason whose listener raises an alert, so a close asked for meanwhile does not hide it.
    @Test
    void anEncodingFailureAfterACloseWasAskedForIsStillReported() {
        var failure = new IllegalStateException("defective body");
        output.start();
        source.offer(new ResponseBody() {
            @Override
            public void applyHeadersTo(HttpServletResponse response) {
            }

            @Override
            public void writeTo(OutputStream outputStream) {
                output.close();
                throw failure;
            }
        });

        output.sourceReady();

        assertThat(endReasons).containsExactly(new EndReason.EncodingFailed(failure));
    }

    /// A body offered after the drain found nothing ready, but before the drain went idle, starts no drain of its own, so the drain going idle writes it.
    @Test
    void aBodyOfferedAsTheDrainGoesIdleIsWritten() {
        source.onEmptyTake(() -> source.offer("late"));

        output.start();

        assertThat(stream.output()).isEqualTo("late");
        assertThat(asyncContext.isCompleted()).isFalse();
        output.close();
    }

    @Test
    void aSourceFinishingAsTheDrainGoesIdleCompletesTheExchange() {
        source.onReadyCheck(source::finish);

        output.start();

        assertThat(asyncContext.completions()).isEqualTo(1);
        assertThat(endReasons).containsExactly(EndReason.Finished.INSTANCE);
    }

    /// An offer made by another thread while the first drain goes idle starts a second drain; the first drain leaves the stream to the second, and every body
    /// is written once.
    @Test
    void aDrainGoingIdleLeavesTheStreamToADrainAnOfferStartedMeanwhile() {
        output.start();
        stream.stallReading();
        source.offer("first");
        output.sourceReady();
        // The second drain's write is held, so the second drain holds the stream when the drain going idle checks the source again.
        source.onReadyCheck(() -> {
            stream.stallReading();
            source.offer("raced");
            output.sourceReady();
            source.offer("after");
            output.sourceReady();
        });

        stream.resumeReading();
        assertThat(stream.output()).isEqualTo("first");
        stream.resumeReading();

        assertThat(stream.output()).isEqualTo("firstracedafter");
        output.close();
    }

    @Test
    void closingWhileADrainFindsNothingEndsTheExchangeOnce() {
        output.start();
        source.onEmptyTake(() -> {
            output.close();
            output.close();
        });
        source.offer("written");

        output.sourceReady();

        assertThat(stream.output()).isEqualTo("written");
        assertThat(asyncContext.completions()).isEqualTo(1);
        assertThat(endReasons).containsExactly(EndReason.Closed.INSTANCE);
    }

    @Test
    void closingAsTheSourceFinishesEndsTheExchangeAsClosed() {
        source.onEmptyTake(() -> {
            source.finish();
            output.close();
        });

        output.start();

        assertThat(asyncContext.completions()).isEqualTo(1);
        assertThat(endReasons).containsExactly(EndReason.Closed.INSTANCE);
    }

    /// Part of the response is on the wire already, so its status cannot be changed; the exchange ends with the body truncated.
    @Test
    void anUnencodableBodyAfterTheResponseWasCommittedEndsTheExchangeWithoutResettingIt() {
        when(response.isCommitted()).thenReturn(true);
        source.offer(UnserialisablePayload.createJsonBody());

        output.start();

        verify(response, never()).reset();
        verify(response, never()).setStatus(INTERNAL_SERVER_ERROR_500);
        assertThat(endReasons).singleElement().isInstanceOf(EndReason.EncodingFailed.class);
    }

    /// The container runs nothing more for an exchange it has completed, so a body offered then ends the output at once.
    @Test
    void aBodyOfferedOnceTheContainerRunsNothingMoreEndsTheOutput() {
        asyncContext.complete();
        source.offer("too late");

        output.sourceReady();

        assertThat(stream.output()).isEmpty();
        assertThat(endReasons).singleElement().isInstanceOf(EndReason.Failed.class);
    }

    /// The held write keeps its chunk until the container has finished with the exchange, which is when the chunk returns to the pool; the leak check in
    /// tearDown asserts this.
    @Test
    void theContainerCompletingAnExchangeWithAWriteHeldEndsTheOutput() {
        output.start();
        stream.stallReading();
        source.offer("held");
        output.sourceReady();

        asyncContext.complete();

        assertThat(endReasons).singleElement().isInstanceOf(EndReason.Failed.class);
    }

    @Test
    void closingAnOutputWhoseExchangeTheContainerCompletedUnseenIsQuiet() {
        asyncContext.complete();

        output.close();

        assertThat(endReasons).containsExactly(EndReason.Closed.INSTANCE);
    }

    @Test
    void anOutputClosedBeforeItStartsWritesNothing() {
        source.offer("never");
        output.close();

        output.start();

        assertThat(stream.output()).isEmpty();
        assertThat(endReasons).containsExactly(EndReason.Closed.INSTANCE);
    }

    /// An end listener that throws is invoked exactly once; the exception propagates to the drain that ended the exchange, which has nothing further to end.
    @Test
    void anEndListenerThatThrowsIsInvokedOnce() {
        var receivedReasons = new ArrayList<EndReason>();
        var throwingOutput = new ContainerOutput(claim, stream, source, reason -> {
            receivedReasons.add(reason);
            throw new IllegalStateException("listener failed");
        });
        source.finish();

        throwingOutput.start();

        assertThat(receivedReasons).containsExactly(EndReason.Finished.INSTANCE);
        assertThat(asyncContext.completions()).isEqualTo(1);
    }

    /// A [ContainerOutput#close()] call made while the drain encodes a body ends the exchange before any of that body is written.
    @Test
    void closingWhileABodyIsEncodedEndsTheExchangeBeforeItIsWritten() {
        output.start();
        source.offer(new ResponseBody() {
            @Override
            public void applyHeadersTo(HttpServletResponse response) {
            }

            @Override
            public void writeTo(OutputStream outputStream) throws IOException {
                outputStream.write("unsent".getBytes(UTF_8));
                output.close();
            }
        });

        output.sourceReady();

        assertThat(stream.output()).isEmpty();
        assertThat(asyncContext.completions()).isEqualTo(1);
        assertThat(endReasons).containsExactly(EndReason.Closed.INSTANCE);
    }

    /// A close call made after the drain starts a write and before it calls [ServletOutputStream#isReady()] ends the exchange, and nothing further is
    /// written.
    @Test
    void closingAsTheDrainChecksItsWriteEndsTheExchangeOnce() {
        HookedOutput hookedOutput = startHookedOutput();
        hookedOutput.stream().beforeReadyCheck(hookedOutput.output()::close);
        source.offer("written");

        hookedOutput.output().sourceReady();
        source.offer("too late");
        hookedOutput.output().sourceReady();

        assertThat(stream.output()).isEqualTo("written");
        assertThat(asyncContext.completions()).isEqualTo(1);
        assertThat(endReasons).containsExactly(EndReason.Closed.INSTANCE);
    }

    /// A write that fails after a close call, made while a drain held the stream, ends the exchange with the reason the close recorded first.
    @Test
    void aWriteFailingAfterACloseWasAskedForEndsTheExchangeAsClosed() {
        HookedOutput hookedOutput = startHookedOutput();
        hookedOutput.stream().beforeWrite(() -> {
            hookedOutput.output().close();
            stream.failWrites(true);
        });
        source.offer("unwritable");

        hookedOutput.output().sourceReady();

        assertThat(asyncContext.completions()).isEqualTo(1);
        assertThat(endReasons).containsExactly(EndReason.Closed.INSTANCE);
    }

    /// An [WriteListener#onWritePossible()] call made while a closing drain holds the stream changes nothing: that drain ends the exchange once.
    @Test
    void aCallBackArrivingWhileADrainIsClosingLeavesTheEndToThatDrain() {
        HookedOutput hookedOutput = startHookedOutput();
        hookedOutput.stream().beforeWrite(() -> {
            hookedOutput.output().close();
            hookedOutput.stream().callBack();
        });
        source.offer("written");

        hookedOutput.output().sourceReady();

        assertThat(stream.output()).isEqualTo("written");
        assertThat(asyncContext.completions()).isEqualTo(1);
        assertThat(endReasons).containsExactly(EndReason.Closed.INSTANCE);
    }

    private HookedOutput startHookedOutput() {
        var hookedStream = new HookedStream(stream);
        var hookedOutput = new ContainerOutput(claim, hookedStream, source, endReasons::add);
        hookedOutput.start();
        return new HookedOutput(hookedStream, hookedOutput);
    }

    private record HookedOutput(HookedStream stream, ContainerOutput output) {}

    /// Bodies a test offers, taken in order, on the test's own thread: every drain here runs inline on it. The hooks stand in for another thread acting at
    /// the one moment a race needs it to.
    private static final class TestSource implements OutputSource {
        private final List<ResponseBody> bodies = new ArrayList<>();
        private boolean finished;
        /// Run once, the next time a take finds nothing ready.
        private Runnable onEmptyTake = () -> {};
        /// Run once, on the next [#hasReady()] call.
        private Runnable onReadyCheck = () -> {};

        void offer(String text) {
            offer(new RawBody(text));
        }

        void offer(ResponseBody body) {
            bodies.add(body);
        }

        void finish() {
            finished = true;
        }

        void onEmptyTake(Runnable action) {
            onEmptyTake = action;
        }

        void onReadyCheck(Runnable action) {
            onReadyCheck = action;
        }

        @Override
        public List<ResponseBody> takeReady() {
            List<ResponseBody> readyBodies = ImmutableList.copyOf(bodies);
            bodies.clear();
            if (readyBodies.isEmpty()) {
                // Cleared before it runs, because the action may start a drain that takes again.
                Runnable action = onEmptyTake;
                onEmptyTake = () -> {};
                action.run();
            }
            return readyBodies;
        }

        @Override
        public boolean hasReady() {
            Runnable action = onReadyCheck;
            onReadyCheck = () -> {};
            action.run();
            return !bodies.isEmpty();
        }

        @Override
        public boolean isFinished() {
            return finished && bodies.isEmpty();
        }
    }

    /// A [ContainerStream] over a [CapturingServletOutputStream] that runs a hook just before each write and just before each [#isReady()] check.
    private static final class HookedStream implements ContainerStream {
        private final CapturingServletOutputStream delegate;
        /// Run once, on the next [#isReady()] call.
        private Runnable beforeReadyCheck = () -> {};
        /// Run once, on the next [#write] call.
        private Runnable beforeWrite = () -> {};
        /// `null` until the output registers its listener.
        private @Nullable WriteListener writeListener;

        HookedStream(CapturingServletOutputStream delegate) {
            this.delegate = checkNotNull(delegate, "delegate");
        }

        void beforeReadyCheck(Runnable action) {
            beforeReadyCheck = action;
        }

        void beforeWrite(Runnable action) {
            beforeWrite = action;
        }

        /// Invokes the listener's [WriteListener#onWritePossible()], as the container does once it is ready for a write.
        void callBack() {
            assert writeListener != null : "the output has registered no listener";
            asUnchecked(writeListener::onWritePossible);
        }

        @Override
        public ByteBufferPool chunkPool() {
            return delegate.chunkPool();
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            writeListener = listener;
            delegate.setWriteListener(listener);
        }

        @Override
        public boolean isReady() {
            Runnable action = beforeReadyCheck;
            beforeReadyCheck = () -> {};
            action.run();
            return delegate.isReady();
        }

        @Override
        public void write(ByteBuffer buffer) throws IOException {
            Runnable action = beforeWrite;
            beforeWrite = () -> {};
            action.run();
            delegate.write(buffer);
        }
    }

    /// The text of a body exactly as written, so assertions read the wire bytes.
    private record RawBody(String text) implements ResponseBody {
        @Override
        public void applyHeadersTo(HttpServletResponse response) {
        }

        @Override
        public void writeTo(OutputStream output) throws IOException {
            output.write(text.getBytes(UTF_8));
        }
    }
}
