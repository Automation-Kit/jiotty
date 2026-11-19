package net.yudichev.jiotty.user.ui.io.testing;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.user.ui.io.ExchangeContainer;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;

/// An [AsyncContext], and the [ExchangeContainer] behind it, that behave as a container's do where a test can see them:
/// - [#execute(Runnable)] runs the task at once on the calling thread, unless [#holdTasks()] holds it, whatever the state of the exchange;
/// - [#complete()] invokes every listener's [AsyncListener#onComplete(AsyncEvent)], recycles the context — from then on [#getRequest()] throws — and then
///   finishes the container's part in the exchange, which runs its completion listeners; [#holdContainerCompletion()] defers that last step. A second
///   [#complete()] throws;
/// - [#abort()] ends the exchange as a container does when the client goes away: no listener is invoked, and the container finishes with it at once;
///   [#abort(Throwable)] does the same and records the cause;
/// - a scheduled task runs when the test calls [#expireDeadlines()].
public final class FakeAsyncContext implements AsyncContext, ExchangeContainer {
    private final ServletRequest request;
    private final ServletResponse response;
    private final List<AsyncListener> listeners = new ArrayList<>();
    private final List<Runnable> completionListeners = new ArrayList<>();
    private final List<ScheduledTask> scheduledTasks = new ArrayList<>();
    private final List<Runnable> heldTasks = new ArrayList<>();
    private boolean holdingTasks;
    private long timeout;
    private int completions;
    private boolean recycled;
    private boolean containerCompletionHeld;
    private boolean containerCompleted;
    private int dispatches;
    private @Nullable RuntimeException executionFailure;
    private @Nullable RuntimeException completionFailure;
    private @Nullable RuntimeException scheduleFailure;
    private @Nullable RuntimeException abortFailure;
    private @Nullable RuntimeException dispatchFailure;
    private @Nullable Throwable abortCause;

    public FakeAsyncContext(ServletRequest request, ServletResponse response) {
        this.request = checkNotNull(request, "request");
        this.response = checkNotNull(response, "response");
    }

    public boolean isCompleted() {
        return completions > 0;
    }

    public int completions() {
        return completions;
    }

    public int dispatches() {
        return dispatches;
    }

    /// Whether the container has finished with the exchange, which runs its completion listeners.
    public boolean isContainerCompleted() {
        return containerCompleted;
    }

    /// Ends the exchange as a container aborts one, as when the client goes away: without completing it, so no [AsyncListener] is invoked, and with the
    /// container finishing its part at once.
    public void abort() {
        recycled = true;
        finishContainerCompletion();
    }

    @Override
    public void abort(Throwable cause) {
        checkNotNull(cause, "cause");
        if (abortFailure != null) {
            throw abortFailure;
        }
        abortCause = cause;
        abort();
    }

    /// Returns why the exchange was aborted through [#abort(Throwable)]; empty when it was not.
    public Optional<Throwable> abortCause() {
        return Optional.ofNullable(abortCause);
    }

    /// Makes [#abort(Throwable)] throw `failure` from now on without aborting.
    public void failAbortsWith(RuntimeException failure) {
        abortFailure = checkNotNull(failure, "failure");
    }

    /// Defers the container's part in a later [#complete()] until [#releaseContainerCompletion()], as a container does when it finishes the exchange on
    /// another thread, after the last write's callback.
    public void holdContainerCompletion() {
        containerCompletionHeld = true;
    }

    /// Finishes the container's part in an exchange [#complete()] has completed while it was held.
    public void releaseContainerCompletion() {
        containerCompletionHeld = false;
        if (completions > 0) {
            finishContainerCompletion();
        }
    }

    /// Runs every scheduled task not cancelled, as the container's timer does once their delays have passed.
    public void expireDeadlines() {
        for (ScheduledTask scheduledTask : List.copyOf(scheduledTasks)) {
            if (!scheduledTask.cancelled) {
                scheduledTask.cancelled = true;
                scheduledTask.task.run();
            }
        }
    }

    /// Returns the delays of the tasks scheduled and not cancelled.
    public List<Duration> pendingDeadlines() {
        return scheduledTasks.stream().filter(scheduledTask -> !scheduledTask.cancelled).map(scheduledTask -> scheduledTask.delay).collect(toImmutableList());
    }

    /// Makes [#schedule(Duration, Runnable)] throw `failure` from now on, as a container's timer does with a [RejectedExecutionException] once stopped.
    public void failSchedulingWith(RuntimeException failure) {
        scheduleFailure = checkNotNull(failure, "failure");
    }

    /// Makes [#complete()] throw from now on without completing, as a container does when the exchange is mid-dispatch.
    public void refuseCompletion() {
        failCompletionWith(new IllegalStateException("the exchange is being dispatched"));
    }

    /// Makes [#complete()] throw `failure` from now on without completing.
    public void failCompletionWith(RuntimeException failure) {
        completionFailure = checkNotNull(failure, "failure");
    }

    /// Makes [#execute(Runnable)] throw `failure` from now on without running the task, as a container whose thread pool has stopped does with a
    /// [RejectedExecutionException].
    public void failExecutionsWith(RuntimeException failure) {
        executionFailure = checkNotNull(failure, "failure");
    }

    /// Makes every dispatch throw `failure` from now on without counting it, as a container whose thread pool has stopped does with a
    /// [RejectedExecutionException].
    public void failDispatchesWith(RuntimeException failure) {
        dispatchFailure = checkNotNull(failure, "failure");
    }

    /// Holds the tasks that later [#execute(Runnable)] calls accept, until [#runHeldTasks()], as a container does until a pool thread is free.
    public void holdTasks() {
        holdingTasks = true;
    }

    /// Runs the tasks held so far, in the order accepted, and runs later ones at once again.
    public void runHeldTasks() {
        holdingTasks = false;
        List<Runnable> tasks = List.copyOf(heldTasks);
        heldTasks.clear();
        tasks.forEach(Runnable::run);
    }

    @Override
    public void addCompletionListener(Runnable onCompleted) {
        completionListeners.add(checkNotNull(onCompleted, "onCompleted"));
    }

    @Override
    public Closeable schedule(Duration delay, Runnable task) {
        if (scheduleFailure != null) {
            throw scheduleFailure;
        }
        var scheduledTask = new ScheduledTask(delay, task);
        scheduledTasks.add(scheduledTask);
        return () -> scheduledTask.cancelled = true;
    }

    @Override
    public void execute(Runnable task) {
        if (executionFailure != null) {
            throw executionFailure;
        }
        if (holdingTasks) {
            heldTasks.add(task);
        } else {
            task.run();
        }
    }

    @Override
    public ServletRequest getRequest() {
        checkState(!recycled, "the context has been recycled");
        return request;
    }

    @Override
    public ServletResponse getResponse() {
        return response;
    }

    @Override
    public boolean hasOriginalRequestAndResponse() {
        return true;
    }

    @Override
    public void dispatch() {
        if (dispatchFailure != null) {
            throw dispatchFailure;
        }
        dispatches++;
    }

    @Override
    public void dispatch(String path) {
        dispatch();
    }

    @Override
    public void dispatch(ServletContext context, String path) {
        dispatch();
    }

    @Override
    public void complete() {
        if (completionFailure != null) {
            throw completionFailure;
        }
        checkState(completions == 0, "the exchange has completed already");
        completions++;
        for (AsyncListener listener : List.copyOf(listeners)) {
            asUnchecked(() -> listener.onComplete(new AsyncEvent(this, request, response)));
        }
        recycled = true;
        if (!containerCompletionHeld) {
            finishContainerCompletion();
        }
    }

    /// @throws UnsupportedOperationException always: a container validates the exchange when a task started this way runs, and fails on its own thread if
    ///                                       the exchange has completed
    @Override
    public void start(Runnable task) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addListener(AsyncListener listener) {
        listeners.add(checkNotNull(listener, "listener"));
    }

    @Override
    public void addListener(AsyncListener listener, ServletRequest servletRequest, ServletResponse servletResponse) {
        addListener(listener);
    }

    @Override
    public <T extends AsyncListener> T createListener(Class<T> type) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getTimeout() {
        return timeout;
    }

    @Override
    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }

    /// Over a copy, because a listener may add another while it runs.
    private void finishContainerCompletion() {
        checkState(!containerCompleted, "the container has finished with the exchange already");
        containerCompleted = true;
        List.copyOf(completionListeners).forEach(Runnable::run);
    }

    private static final class ScheduledTask {
        private final Duration delay;
        private final Runnable task;
        private boolean cancelled;

        ScheduledTask(Duration delay, Runnable task) {
            this.delay = checkNotNull(delay, "delay");
            this.task = checkNotNull(task, "task");
        }
    }
}
