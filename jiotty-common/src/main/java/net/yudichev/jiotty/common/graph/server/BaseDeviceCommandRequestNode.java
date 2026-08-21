package net.yudichev.jiotty.common.graph.server;

import com.google.errorprone.annotations.OverridingMethodsMustInvokeSuper;
import net.yudichev.jiotty.common.lang.Closeable;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Objects.requireNonNullElse;

public abstract class BaseDeviceCommandRequestNode<R> extends BaseServerNode implements DeviceCommandRequestNode<R> {
    private final Duration retryDelay;
    private final boolean panicOnFatalFailure;
    private final int maxRetriesBeforeFatalFailure;
    private @Nullable DeviceRequest<R> request;
    private @Nullable Closeable pendingRequestRetrySchedule;
    private boolean retryDue;
    /// `-1` means a trigger based retry is due
    private int retryCount;
    private @Nullable String lastFailure;
    /// The request [#forgetRequest()] asked to abandon, consumed by the next [#doWave()], or `null` when no forget is pending.
    private @Nullable DeviceRequest<R> pendingRequestToForget;

    protected BaseDeviceCommandRequestNode(GraphRunner runner,
                                           String name,
                                           Duration retryDelay,
                                           int maxRetriesBeforeFatalFailure,
                                           boolean panicOnFatalFailure) {
        super(runner, name);
        this.retryDelay = checkNotNull(retryDelay);
        this.panicOnFatalFailure = panicOnFatalFailure;
        checkArgument(maxRetriesBeforeFatalFailure >= 0);
        this.maxRetriesBeforeFatalFailure = maxRetriesBeforeFatalFailure;
    }

    @Override
    @OverridingMethodsMustInvokeSuper
    public boolean doWave() {
        DeviceRequest<R> requestToForget = pendingRequestToForget;
        pendingRequestToForget = null;

        if (request == null) {
            resetRetryState();
            return false;
        }

        // identity, not equality: a request created after the forget is a fresh instance the caller still wants, even when its payload is identical
        if (requestToForget == request || shouldForgetRequest(request)) {
            logger.debug("Asked to forget request {}", request);
            return completeRequest();
        }

        if (request.sent()) {
            if (deviceStateIndicatesRequestSuccessful(request.payload())) {
                logger.info("Successfully executed: {}", request);
                return completeRequest();
            } else {
                if (retryDue) {
                    // a retry the device cannot accept yet stays due, so the state change that makes it sendable re-waves this node; consuming it here would
                    // strand the request, because the schedule that raised it has already fired and nothing else ever raises it again
                    if (retryCount >= 0 && retryCount < maxRetriesBeforeFatalFailure && !deviceStateValidForRequestToBeSent()) {
                        logger.debug("Awaiting valid device state before retrying the request");
                        return false;
                    }
                    retryDue = false;
                    if (++retryCount == 0) {
                        logger.debug("Trigger-based retry of {}", request);
                        doExecuteRequest(retryCount);
                    } else if (retryCount > maxRetriesBeforeFatalFailure) {
                        logger.debug("Request fatally failed: {}", request);
                        boolean shouldKeepRetrying = onCommandFailedFatally(lastFailure, () -> runner.executor().execute(() -> {
                            logger.debug("Retry trigger for {} fired", request);
                            retryCount = -1; // mark
                            retryDue = true;
                            triggerMeAndParentsInNewWave("Retry trigger for " + request);
                        }));
                        if (shouldKeepRetrying) {
                            logger.info("Retry {}/{} of {} failed, will await retry trigger to restart", retryCount, maxRetriesBeforeFatalFailure, request);
                            resetRetryState();
                        } else {
                            var failure = requireNonNullElse(lastFailure,
                                                             "Retry count exceeded: device state did not confirm that the command was successful");
                            request = request.asFailed(failure);
                            if (panicOnFatalFailure) {
                                runner.panic(request + " retried " + (retryCount - 1) + " times, last failure: " + failure);
                            } else {
                                logger.debug("Retry {}/{} of {} failed fatally, but configured not to panic",
                                             retryCount,
                                             maxRetriesBeforeFatalFailure,
                                             request);
                            }
                        }
                    } else {
                        logger.info("Retry {}/{} of {}", retryCount, maxRetriesBeforeFatalFailure, request);
                        doExecuteRequest(retryCount);
                    }
                    return true;
                } else {
                    logger.info("Awaiting the effect of {}", request);
                    return false;
                }
            }
        } else if (deviceStateValidForRequestToBeSent()) {
            if (deviceStateIndicatesRequestSuccessful(request.payload())) {
                logger.info("Device already in the requested state: {}", request);
                return completeRequest();
            }
            logger.info("Executing request {}", request);
            request = request.asSent();
            doExecuteRequest(retryCount);
            return true;
        } else {
            logger.debug("Awaiting valid device state for sending request");
            return false;
        }
    }

    @Override
    public void close() {
        Closeable.closeIfNotNull(pendingRequestRetrySchedule);
        super.close();
    }

    @Override
    public final void logState(String when) {
        if (logger.isDebugEnabled()) {
            var additionalStateKey = additionalLoggingStateKey();
            var additionalStateValue = additionalLoggingStateValue();
            logger.debug("{}: request={}, retryScheduleArmed={}, retryDue={}, retryCount={}, lastFailure={}, pendingRequestToForget={}{}{}", when, request,
                         pendingRequestRetrySchedule != null, retryDue, retryCount, lastFailure, pendingRequestToForget,
                         additionalStateKey == null ? "" : additionalStateKey,
                         additionalStateValue == null ? "" : additionalStateValue);
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    @Override
    public final boolean requestPending() {
        return request != null;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    @Override
    public final @Nullable DeviceRequest<R> currentRequest() {
        return request;
    }

    /// Abandons the request pending at the moment of the call, without sending or retrying it further, taking effect in a new wave. A request created
    /// before that wave runs supersedes the call.
    public final void forgetRequest() {
        pendingRequestToForget = request;
        triggerInNewWave(name() + " asked to forget request");
    }

    /// Called in-wave before anything else - return true to immediately forget the request and reset to an idle state
    protected boolean shouldForgetRequest(DeviceRequest<R> request) {
        return false;
    }

    /// @param retryTrigger invoke this to request the retry, must be used in combination with returning `true` from this method
    /// @return `true` to reset the retry counter and keep retrying, otherwise panic (unless configured not to via `panicOnFailure=false`)
    protected boolean onCommandFailedFatally(String lastFailure, Runnable retryTrigger) {
        return false;
    }

    protected @Nullable Object additionalLoggingStateKey() {
        return null;
    }

    protected @Nullable Object additionalLoggingStateValue() {
        return null;
    }

    protected final void createRequestIfNotAlreadyInProgress(Supplier<DeviceRequest<R>> requestFactory) {
        pendingRequestToForget = null; // asking for a request supersedes any forget that has not been waved yet
        var newRequest = requestFactory.get();
        if (request == null) {
            request = newRequest;
            triggerInNewWave(name() + " processing new request");
        } else if (!Objects.equals(request.payload(), newRequest.payload())) {
            request = newRequest;
            resetRetryState();
            triggerInNewWave(name() + " processing replaced request");
        } else {
            logger.debug("Same request already in progress: {}", request);
        }
    }

    protected abstract boolean deviceStateValidForRequestToBeSent();

    /// Whether the device state already satisfies `payload`. Invoked after the command is sent and, while [#deviceStateValidForRequestToBeSent()] holds,
    /// before sending it - so a request whose target state already holds completes without a command.
    protected abstract boolean deviceStateIndicatesRequestSuccessful(R payload);

    protected abstract void sendCommand(int retryNumber, R payload, Consumer<String> failureHandler);

    private boolean completeRequest() {
        request = null;
        resetRetryState();
        return true;
    }

    private void resetRetryState() {
        Closeable.closeIfNotNull(pendingRequestRetrySchedule);
        pendingRequestRetrySchedule = null;
        retryDue = false;
        retryCount = 0;
        lastFailure = null;
    }

    private void doExecuteRequest(int retryNumber) {
        assert request != null;
        DeviceRequest<R> requestSent = request;
        logger.debug("Request {}: sending command", request);
        sendCommand(retryNumber, request.payload(), failure -> {
            if (request != null) {
                logger.info("{} failure, will be retried: {}", request, failure);
                lastFailure = failure;
            } else {
                logger.debug("Ignoring delayed failure - request {} already inactive: {}", requestSent, failure);
            }
        });
        assert pendingRequestRetrySchedule == null;
        logger.debug("Scheduling possible retry of {} in {}", request, retryDelay);
        pendingRequestRetrySchedule = runner.executor().schedule(retryDelay, () -> {
            pendingRequestRetrySchedule = null;
            retryDue = true;
            triggerMeAndParentsInNewWave("Retry of " + request);
        });
    }
}
