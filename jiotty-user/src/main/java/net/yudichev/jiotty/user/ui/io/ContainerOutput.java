package net.yudichev.jiotty.user.ui.io;

import com.google.errorprone.annotations.concurrent.GuardedBy;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.user.ui.io.DrainState.Closing;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.rest.HttpStatuses.INTERNAL_SERVER_ERROR_500;
import static net.yudichev.jiotty.user.ui.io.DrainState.Phase.CLOSED;
import static net.yudichev.jiotty.user.ui.io.DrainState.Phase.DRAINING;
import static net.yudichev.jiotty.user.ui.io.DrainState.Phase.IDLE;
import static net.yudichev.jiotty.user.ui.io.DrainState.Phase.PARKED;
import static net.yudichev.jiotty.user.ui.io.DrainState.Phase.QUEUED;

/// A non-blocking writer of one async exchange's response, created through the exchange's [AsyncExchange.Claim], which completes the exchange once its
/// [OutputSource] has finished or the exchange has ended. [#sourceReady()] and [#close()] may be called from any thread, and every encoding, every write
/// and the completion run on a container thread.
// FieldAccessNotGuarded: the guard is ownership of the stream, taken by a compare-and-set on `state` rather than a lock, so IntelliJ cannot see it held.
@SuppressWarnings("FieldAccessNotGuarded")
public final class ContainerOutput {
    private static final Logger logger = LogManager.getLogger(ContainerOutput.class);

    /// The completion of an exchange the container has completed already.
    private static final Runnable NO_COMPLETION = () -> {};
    /// The end reason reported when the container has finished with the exchange before this output ended it.
    private static final EndReason ENDED_BY_CONTAINER = new EndReason.Failed(new CancellationException("the container ended the exchange"));
    /// The end reason reported when the container rejects a drain; the exchange has reported why already.
    private static final EndReason DRAIN_REJECTED = new EndReason.Failed(new CancellationException("the container rejected a drain"));

    private final AsyncExchange.Claim claim;
    private final AsyncExchange exchange;
    private final ContainerStream stream;
    private final OutputSource source;
    private final Consumer<EndReason> endListener;
    /// Moved by the source owner's thread offering bodies and by the container threads draining them, which no single thread can own: the owner's thread
    /// must never write, and the container's callbacks arrive on whichever pool thread it picks.
    private final AtomicReference<DrainState> state = new AtomicReference<>(IDLE);
    /// Drains only when it takes the stream from [DrainState.Phase#QUEUED]: by the time a container thread runs it, a callback or an end may have moved the
    /// state on.
    private final Runnable drainTask = () -> {
        if (state.compareAndSet(QUEUED, DRAINING)) {
            drain();
        }
    };
    /// The encoded bodies being written; `null` when no batch is being written.
    @GuardedBy("state")
    private @Nullable PooledChunkOutputStream batch;
    /// The index of the next chunk of [#batch] to write.
    @GuardedBy("state")
    private int nextChunk;

    /// @param endListener invoked once, on the thread that ends the exchange, with the reason it ended
    ContainerOutput(AsyncExchange.Claim claim, ContainerStream stream, OutputSource source, Consumer<EndReason> endListener) {
        this.claim = checkNotNull(claim, "claim");
        exchange = claim.exchange();
        this.stream = checkNotNull(stream, "stream");
        this.source = checkNotNull(source, "source");
        this.endListener = checkNotNull(endListener, "endListener");
    }

    /// Registers for the container's callbacks, which starts the first drain. Must run on a container thread, before [#sourceReady()].
    public void start() {
        exchange.whenEnded(this::onExchangeEnded);
        // An exchange that has ended already has just ended this output, and takes no listener.
        if (state.get() != CLOSED) {
            stream.setWriteListener(new StreamListener());
        }
    }

    /// Ends the exchange, aborting any write in progress.
    public void close() {
        end(EndReason.Closed.INSTANCE, Completion.ON_A_CONTAINER_THREAD);
    }

    /// Signals that the source has a body ready or has finished, and starts a drain on a container thread unless one is running or queued.
    public void sourceReady() {
        if (state.compareAndSet(IDLE, QUEUED) && !exchange.runOnContainerThread(drainTask)) {
            // The container rejected the drain, so the output ends here, unless a callback or an end has taken the stream meanwhile.
            if (state.compareAndSet(QUEUED, CLOSED)) {
                claim.completeNow();
                endListener.accept(DRAIN_REJECTED);
            }
        }
    }

    private void drain() {
        try {
            while (true) {
                PooledChunkOutputStream currentBatch = batch;
                if (currentBatch != null && nextChunk < currentBatch.chunkCount()) {
                    stream.write(currentBatch.chunk(nextChunk++));
                    if (!awaitWrite()) {
                        return;
                    }
                } else {
                    releaseBatch();
                    List<? extends ResponseBody> bodies = source.takeReady();
                    if (bodies.isEmpty()) {
                        if (source.isFinished()) {
                            finish();
                            return;
                        }
                        if (!keepDrainingAfterIdle()) {
                            return;
                        }
                    } else if (!encode(bodies)) {
                        return;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // A client that went away is routine; anything else thrown here is a defect in this class or its source.
            Level level = e instanceof IOException ? Level.DEBUG : Level.INFO;
            if (logger.isEnabled(level)) {
                logger.log(level, "[{}] Writing the response failed", exchange.id(), e);
            }
            if (!isClosed(state.get())) {
                // The write in flight may still hold its chunk, so the batch waits for the exchange to end.
                endWhileDraining(new EndReason.Failed(e), true);
            }
        }
    }

    /// Moves the stream to [DrainState.Phase#PARKED] once a write has started, and takes it back when that write has completed already.
    ///
    /// @return `true` when the write has completed and this thread holds the stream; `false` when the container calls back on its completion, or the
    ///         exchange is ending
    private boolean awaitWrite() {
        // Parked before calling isReady(), because a `false` from it lets the container call back at once, on another thread, expecting the stream free.
        if (!state.compareAndSet(DRAINING, PARKED)) {
            endWhileDraining(closingReason(), true);
            return false;
        }
        return stream.isReady() && state.compareAndSet(PARKED, DRAINING);
    }

    private void finish() {
        if (state.compareAndSet(DRAINING, CLOSED)) {
            claim.completeNow();
            endListener.accept(EndReason.Finished.INSTANCE);
        } else {
            endWhileDraining(closingReason(), false);
        }
    }

    /// Moves the stream to [DrainState.Phase#IDLE], then takes it back when a body arrived in between, because the offer of that body started no drain
    /// while the stream was held.
    private boolean keepDrainingAfterIdle() {
        if (!state.compareAndSet(DRAINING, IDLE)) {
            endWhileDraining(closingReason(), false);
            return false;
        }
        return (source.hasReady() || source.isFinished()) && state.compareAndSet(IDLE, DRAINING);
    }

    private boolean encode(List<? extends ResponseBody> bodies) {
        var chunks = new PooledChunkOutputStream(stream.chunkPool(), true);
        try {
            for (ResponseBody body : bodies) {
                body.writeTo(chunks);
            }
        } catch (IOException | RuntimeException e) {
            chunks.release();
            HttpServletResponse response = claim.response();
            if (!response.isCommitted()) {
                response.reset();
                response.setStatus(INTERNAL_SERVER_ERROR_500);
            }
            endWhileDraining(new EndReason.EncodingFailed(e), false);
            return false;
        }
        // Checked once per batch: a close made while the bodies encoded ends the exchange before any of them is written.
        if (state.get() instanceof Closing(EndReason reason)) {
            chunks.release();
            endWhileDraining(reason, false);
            return false;
        }
        batch = chunks;
        nextChunk = 0;
        return true;
    }

    private static boolean isClosed(DrainState drainState) {
        return switch (drainState) {
            case DrainState.Phase.CLOSED -> true;
            case DrainState.Phase.IDLE, DrainState.Phase.QUEUED, DrainState.Phase.DRAINING, DrainState.Phase.PARKED -> false;
            case Closing _ -> false;
        };
    }

    /// Ends the exchange from the thread holding the stream, the only thread that moves the state out of [DrainState.Phase#DRAINING] or [Closing]. It reports
    /// the reason a concurrent end recorded, unless `reason` is an encoding failure, which is a defect and the only reason whose listener raises an alert.
    private void endWhileDraining(EndReason reason, boolean writeInProgress) {
        DrainState previous = state.getAndSet(CLOSED);
        assert previous == DRAINING || previous instanceof Closing : "only the thread holding the stream ends it while draining, found " + previous;
        EndReason reported = !(reason instanceof EndReason.EncodingFailed) && previous instanceof Closing(EndReason requested) ? requested : reason;
        if (writeInProgress) {
            abandon(batch);
        } else {
            releaseBatch();
        }
        batch = null;
        claim.completeNow();
        endListener.accept(reported);
    }

    private EndReason closingReason() {
        return ((Closing) state.get()).reason();
    }

    private void end(EndReason reason, Completion completion) {
        while (true) {
            DrainState current = state.get();
            switch (current) {
                case DrainState.Phase.IDLE, DrainState.Phase.QUEUED, DrainState.Phase.PARKED -> {
                    // No thread holds the stream: a queued drain that runs later finds it closed, and a parked write may still hold the batch's chunk.
                    if (state.compareAndSet(current, CLOSED)) {
                        abandon(batch);
                        batch = null;
                        Runnable exchangeCompletion = switch (completion) {
                            case ON_THIS_THREAD -> claim::completeNow;
                            case ON_A_CONTAINER_THREAD -> claim::complete;
                            case DONE_BY_CONTAINER -> NO_COMPLETION;
                        };
                        exchangeCompletion.run();
                        endListener.accept(reason);
                        return;
                    }
                }
                case DrainState.Phase.DRAINING -> {
                    if (state.compareAndSet(current, new Closing(reason))) {
                        return;
                    }
                }
                case DrainState.Phase.CLOSED -> {
                    return;
                }
                case Closing _ -> {
                    return;
                }
            }
        }
    }

    /// The container has finished with the exchange. Nothing but this output's holder completes it normally, so it was aborted, as when the client went
    /// away, or this output completed it itself.
    private void onExchangeEnded() {
        end(ENDED_BY_CONTAINER, Completion.DONE_BY_CONTAINER);
    }

    private void releaseBatch() {
        PooledChunkOutputStream currentBatch = batch;
        if (currentBatch != null) {
            currentBatch.release();
            batch = null;
        }
    }

    /// Releases `chunks` once the exchange has ended, when the container has finished or cancelled every write that used them; at once when it has ended
    /// already.
    ///
    /// @param chunks the batch whose chunk a write may still hold; `null` when no batch was in flight
    private void abandon(@Nullable PooledChunkOutputStream chunks) {
        if (chunks != null) {
            exchange.whenEnded(chunks::release);
        }
    }

    /// How an end completes the exchange.
    private enum Completion {
        /// Completed by the calling container thread.
        ON_THIS_THREAD,
        /// Completed on a container thread, from a caller that may not be one.
        ON_A_CONTAINER_THREAD,
        /// Not completed: the container has finished with the exchange already.
        DONE_BY_CONTAINER
    }

    private final class StreamListener implements WriteListener {
        @Override
        public void onWritePossible() {
            // setWriteListener's own onWritePossible call can arrive after a drain has parked on a write, so PARKED is taken only once isReady() returns
            // true, and the container calls again when that write completes. IDLE is that first call arriving before any drain, and QUEUED one arriving
            // before a queued drain runs, which then finds the stream taken.
            if ((isParked(state.get()) && stream.isReady() && state.compareAndSet(PARKED, DRAINING))
                || state.compareAndSet(IDLE, DRAINING)
                || state.compareAndSet(QUEUED, DRAINING)) {
                drain();
            }
        }

        private static boolean isParked(DrainState drainState) {
            return switch (drainState) {
                case DrainState.Phase.PARKED -> true;
                case DrainState.Phase.IDLE, DrainState.Phase.QUEUED, DrainState.Phase.DRAINING, DrainState.Phase.CLOSED -> false;
                case Closing _ -> false;
            };
        }

        @Override
        public void onError(Throwable throwable) {
            end(new EndReason.Failed(throwable), Completion.ON_THIS_THREAD);
        }
    }
}
