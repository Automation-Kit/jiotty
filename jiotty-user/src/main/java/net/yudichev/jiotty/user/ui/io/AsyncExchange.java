package net.yudichev.jiotty.user.ui.io;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.adminalerts.AdminAlertService;
import net.yudichev.jiotty.common.lang.CloseableGroup;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.adminalerts.AdminAlertSeverity.WARNING;
import static net.yudichev.jiotty.user.ui.io.Deadline.Stall.HANDLING;
import static net.yudichev.jiotty.user.ui.io.Deadline.Stall.RECEIVING_BODY;

/// One async exchange, started by [#start] on the thread dispatching the request, and answered once: by whichever of the application, the [Deadline] and
/// [#failDispatch] takes its [Claim] first. Only the claim's holder touches the exchange's request and response, and only the holder completes it, so no
/// late write reaches the next request the container serves on the same connection.
public final class AsyncExchange {
    @VisibleForTesting
    static final String UNEXPECTED_FAILURE_ALERT_TITLE = "Async exchange failed unexpectedly";
    @VisibleForTesting
    static final String DISPATCH_FAILED_ALERT_TITLE = "Request failed after going async";

    private static final Logger logger = LogManager.getLogger(AsyncExchange.class);

    /// The request attribute holding the exchange a dispatch started, read by [#failDispatch] on the same thread.
    private static final String REQUEST_ATTRIBUTE = AsyncExchange.class.getName();
    private static final Claimed CLAIMED_WITHOUT_OUTPUT = new Claimed(null);

    private final AdminAlertService alertService;
    private final AsyncContext asyncContext;
    private final ExchangeContainer container;
    private final HttpServletResponse response;
    /// The identifier of this exchange in log lines, telling them apart from those of the other exchanges in flight. It is derived from the exchange's
    /// identity alone, and holds nothing about the request or its user.
    private final String id;
    private final Claim claim = new Claim();
    /// What [#claim()] returns to its first caller, the same each time.
    private final Optional<Claim> heldClaim = Optional.of(claim);
    /// Moved by whichever of the application's thread, the container's timer thread and a container thread claims first, and by the body read on container
    /// threads, so no single thread owns it.
    private final AtomicReference<Phase> phase = new AtomicReference<>(Unclaimed.OPEN);
    /// What runs once the container has finished with the exchange; closed by the container's completion listener.
    private final CloseableGroup closedWhenEnded = new CloseableGroup();

    @SuppressWarnings("ThisEscapedInObjectConstruction") // identityHashCode reads only the object's identity, never its state
    private AsyncExchange(AdminAlertService alertService, AsyncContext asyncContext, ExchangeContainer container, HttpServletResponse response) {
        this.alertService = checkNotNull(alertService, "alertService");
        this.asyncContext = checkNotNull(asyncContext, "asyncContext");
        this.container = checkNotNull(container, "container");
        this.response = checkNotNull(response, "response");
        id = Integer.toHexString(System.identityHashCode(this));
    }

    /// Starts `request`'s async cycle as an exchange. Must be called while dispatching `request`, which the container enforces: it refuses to start a cycle
    /// at any other time.
    ///
    /// @param alertService escalates a failure the exchange having ended does not explain
    /// @param deadline     how long the exchange may go unanswered
    public static AsyncExchange start(AdminAlertService alertService, HttpServletRequest request, HttpServletResponse response, Deadline deadline) {
        AsyncContext asyncContext = request.startAsync();
        // No container timeout and no AsyncListener, so the container never completes the exchange normally on its own.
        asyncContext.setTimeout(0);
        var exchange = new AsyncExchange(alertService, asyncContext, ExchangeContainer.of(request, asyncContext), response);
        exchange.container.addCompletionListener(exchange.closedWhenEnded::close);
        request.setAttribute(REQUEST_ATTRIBUTE, exchange);
        if (deadline instanceof Deadline.After after) {
            exchange.scheduleDeadline(after);
        }
        return exchange;
    }

    /// Answers the exchange `request`'s dispatch started, for a dispatch that threw after starting it, since the container sends no response for a request
    /// that has gone async. The failure raises an alert, and the exchange is answered with `answer` unless something has claimed it, whose output is then
    /// closed.
    ///
    /// @return `true` when `request` had started an exchange, which then accounts for the failure; `false` when it had not, and the container sends the
    ///         response
    public static boolean failDispatch(ServletRequest request, Exception failure, Answer answer) {
        if (!(request.getAttribute(REQUEST_ATTRIBUTE) instanceof AsyncExchange exchange)) {
            return false;
        }
        exchange.alertService.raise(WARNING, DISPATCH_FAILED_ALERT_TITLE, logger, '[' + exchange.id + "] The dispatch threw", failure);
        if (exchange.claimFromUnclaimed() != null) {
            exchange.claim.answer(answer, exchange::alertEncodingFailure);
        } else if (exchange.phase.get() instanceof Claimed(ContainerOutput output) && output != null) {
            output.close();
        }
        return true;
    }

    /// Takes the right to answer the exchange, from any thread.
    ///
    /// @return the claim, to the first caller only; empty when the exchange has been claimed already
    public Optional<Claim> claim() {
        return claimFromUnclaimed() == null ? Optional.empty() : heldClaim;
    }

    /// Writes `answer` to the exchange, from any thread, unless it has been claimed already, in which case `answer` is dropped. An answer whose body cannot
    /// be encoded is sent as a bare 500.
    ///
    /// @param onEncodingFailure invoked on a container thread with the failure when `answer`'s body cannot be encoded
    public void answer(Answer answer, Consumer<? super Exception> onEncodingFailure) {
        if (claimFromUnclaimed() != null) {
            claim.answer(answer, onEncodingFailure);
        } else if (logger.isDebugEnabled()) {
            logger.debug("[{}] Dropping an answer with {}: the exchange has been answered", id, answer.status());
        }
    }

    public boolean isClaimed() {
        return phase.get() instanceof Claimed;
    }

    /// Whether the container has yet to finish with the exchange. A failure once it has is explained by that, and is routine.
    public boolean isOpen() {
        return !closedWhenEnded.isClosed();
    }

    /// Runs `onEnded` once the container has finished with the exchange, at once when it has already, on whichever thread finishes it — which may be one
    /// serving the container's network I/O, so `onEnded` must not block. Safe from any thread.
    public void whenEnded(Runnable onEnded) {
        checkNotNull(onEnded, "onEnded");
        closedWhenEnded.add(onEnded::run);
    }

    /// Runs `task` on a container thread, from any thread.
    ///
    /// @return `true` when a container thread was given the task, which it runs unless the exchange ends first; `false` when the container rejected it, which
    ///         is logged, or alerted when the exchange having ended does not explain it
    public boolean runOnContainerThread(Runnable task) {
        try {
            asyncContext.start(task);
            return true;
        } catch (RejectedExecutionException e) {
            // The container's thread pool has stopped, as when the server shuts down.
            if (logger.isDebugEnabled()) {
                logger.debug("[{}] Could not start a task, as the container has stopped", id, e);
            }
            return false;
        } catch (RuntimeException e) {
            reportFailure("start a task", e);
            return false;
        }
    }

    /// Marks the request body as arriving, which a deadline expiring meanwhile reports as [Deadline.Stall#RECEIVING_BODY]. Has no effect once claimed.
    void bodyReceiving() {
        phase.compareAndSet(Unclaimed.OPEN, Unclaimed.AWAITING_BODY);
    }

    void bodyReceived() {
        phase.compareAndSet(Unclaimed.AWAITING_BODY, Unclaimed.OPEN);
    }

    /// @throws IllegalStateException once the exchange has ended
    ServletRequest request() {
        return asyncContext.getRequest();
    }

    HttpServletResponse response() {
        return response;
    }

    String id() {
        return id;
    }

    private void scheduleDeadline(Deadline.After deadline) {
        try {
            // Cancelled once the exchange ends, so an exchange answered early is not held by the timer until the deadline.
            closedWhenEnded.add(container.schedule(deadline.timeout(), () -> onDeadline(deadline)));
        } catch (RejectedExecutionException e) {
            // The container's timer has stopped, as when the server is stopping; the exchange goes on without a deadline.
            if (logger.isDebugEnabled()) {
                logger.debug("[{}] Going on without a deadline, as the container's timer has stopped", id, e);
            }
        }
    }

    /// Runs on the container's timer thread, which also drives every connection's idle timeout, so it only claims, and runs the rest on a container thread.
    private void onDeadline(Deadline.After deadline) {
        Unclaimed unclaimedPhase = claimFromUnclaimed();
        if (unclaimedPhase != null) {
            Deadline.Stall stall = switch (unclaimedPhase) {
                case OPEN -> HANDLING;
                case AWAITING_BODY -> RECEIVING_BODY;
            };
            runOnContainerThread(() -> {
                try {
                    deadline.onExpired().accept(stall);
                } catch (RuntimeException e) {
                    reportFailure("report an expired deadline", e);
                }
                startAnswer(deadline.answer(), deadline.onEncodingFailure());
            });
        }
    }

    /// @return the phase the claim was taken from; `null` when the exchange had been claimed already
    private @Nullable Unclaimed claimFromUnclaimed() {
        while (true) {
            Phase current = phase.get();
            if (!(current instanceof Unclaimed unclaimed)) {
                return null;
            }
            if (phase.compareAndSet(current, CLAIMED_WITHOUT_OUTPUT)) {
                return unclaimed;
            }
        }
    }

    private void startAnswer(Answer answer, Consumer<? super Exception> onEncodingFailure) {
        try {
            ContainerOutput output = claim.createOutput(new SingleBodySource(answer.body()), reason -> {
                if (reason instanceof EndReason.EncodingFailed(Exception cause)) {
                    onEncodingFailure.accept(cause);
                }
            });
            applyStatusAndHeaders(answer);
            output.start();
        } catch (IOException e) {
            // The response's output stream could not be opened, as when the client has gone.
            if (logger.isDebugEnabled()) {
                logger.debug("[{}] Could not open the response to answer with {}", id, answer.status(), e);
            }
            claim.completeNow();
        } catch (RuntimeException e) {
            reportFailure("open the response to answer with " + answer.status(), e);
            claim.completeNow();
        }
    }

    private void applyStatusAndHeaders(Answer answer) {
        response.setStatus(answer.status());
        answer.headers().forEach(response::setHeader);
        answer.body().applyHeadersTo(response);
    }

    private void alertEncodingFailure(Exception failure) {
        alertService.raise(WARNING, UNEXPECTED_FAILURE_ALERT_TITLE, logger, '[' + id + "] Could not encode an answer", failure);
    }

    /// Logs a failure the exchange having ended explains, and raises an alert for any other.
    ///
    /// @param action what failed, completing the sentence "Could not …"
    private void reportFailure(String action, RuntimeException failure) {
        if (isOpen()) {
            alertService.raise(WARNING, UNEXPECTED_FAILURE_ALERT_TITLE, logger, '[' + id + "] Could not " + action, failure);
        } else if (logger.isDebugEnabled()) {
            logger.debug("[{}] Could not {}, as the exchange has ended", id, action, failure);
        }
    }

    /// The right to answer the exchange, which one party holds: the first to call [AsyncExchange#claim()]. Only the holder touches the exchange's request and
    /// response, and only the holder completes it.
    public final class Claim {
        private Claim() {
        }

        /// Writes `answer` to the exchange, from any thread. An answer whose body cannot be encoded is sent as a bare 500.
        ///
        /// @param onEncodingFailure invoked on a container thread with the failure when `answer`'s body cannot be encoded
        public void answer(Answer answer, Consumer<? super Exception> onEncodingFailure) {
            runOnContainerThread(() -> startAnswer(answer, onEncodingFailure));
        }

        /// Creates the output that writes the bodies `source` returns, and completes the exchange once `source` has finished or the exchange ends
        /// otherwise. Must be called on a container thread, which then [starts][ContainerOutput#start()] it.
        ///
        /// @param endListener invoked once, on the thread that ends the exchange, with the reason it ended
        /// @throws IOException if the response's output stream cannot be opened
        public ContainerOutput createOutput(OutputSource source, Consumer<EndReason> endListener) throws IOException {
            var output = new ContainerOutput(this, ContainerStream.of(request(), response), source, endListener);
            // Recorded, so that a dispatch throwing after this point ends the output rather than leaving it to a holder that has gone.
            phase.compareAndSet(CLAIMED_WITHOUT_OUTPUT, new Claimed(output));
            return output;
        }

        /// Completes, on a container thread, an exchange whose response the holder has written in full.
        public void complete() {
            runOnContainerThread(this::completeNow);
        }

        /// Completes the exchange from the calling container thread. A failure is logged when the exchange has ended already, and alerted otherwise.
        public void completeNow() {
            try {
                asyncContext.complete();
            } catch (RuntimeException e) {
                reportFailure("complete the exchange", e);
            }
        }

        /// Ends the exchange without completing its response, for a holder whose response failed after part of it was sent, so the client sees the response
        /// cut off. Safe from any thread, once the holder has stopped writing; has no effect once the exchange has ended.
        ///
        /// @param cause why the response failed
        public void abort(Throwable cause) {
            checkNotNull(cause, "cause");
            // Once ended, the container may be serving the connection's next request, which an abort would cut off instead.
            if (isOpen()) {
                try {
                    container.abort(cause);
                } catch (RuntimeException e) {
                    reportFailure("abort the exchange", e);
                }
            }
        }

        /// Dispatches the request to the container again, which ends this exchange's part in it: a later dispatch starts its own.
        public void dispatch() {
            request().removeAttribute(REQUEST_ATTRIBUTE);
            asyncContext.dispatch();
        }

        /// @throws IllegalStateException once the exchange has ended
        public ServletRequest request() {
            return asyncContext.getRequest();
        }

        public HttpServletResponse response() {
            return response;
        }

        AsyncExchange exchange() {
            return AsyncExchange.this;
        }
    }

    /// Who may answer the exchange.
    private sealed interface Phase {}

    private enum Unclaimed implements Phase {
        OPEN,
        /// The request body is arriving.
        AWAITING_BODY
    }

    /// @param output the output the claim's holder created; `null` until it creates one
    private record Claimed(@Nullable ContainerOutput output) implements Phase {}

    /// An [OutputSource] that returns one body from its first [#takeReady()] and is finished from then on.
    @VisibleForTesting
    static final class SingleBodySource implements OutputSource {
        private final List<ResponseBody> bodies;
        private boolean taken;

        SingleBodySource(ResponseBody body) {
            bodies = ImmutableList.of(body);
        }

        @Override
        public List<ResponseBody> takeReady() {
            if (taken) {
                return ImmutableList.of();
            }
            taken = true;
            return bodies;
        }

        @Override
        public boolean hasReady() {
            return !taken;
        }

        @Override
        public boolean isFinished() {
            return taken;
        }
    }
}
