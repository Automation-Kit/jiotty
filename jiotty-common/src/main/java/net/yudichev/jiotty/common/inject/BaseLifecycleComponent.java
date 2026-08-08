package net.yudichev.jiotty.common.inject;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkState;
import static net.yudichev.jiotty.common.lang.Locks.inLock;

@SuppressWarnings("AbstractClassWithoutAbstractMethods") // designed for extension
public abstract class BaseLifecycleComponent implements LifecycleComponent {
    private final Lock lifecycleStateLock = new ReentrantLock();
    /// True from the moment [#start()] begins until [#stop()] finishes. [#ifNotStopped(Runnable)] reads it from producer callback threads without the
    /// lifecycle lock, so it is an [AtomicBoolean] read there opaquely: opaque access is coherent — a reader eventually observes the write and never sees it
    /// out of order with later writes to the same variable — while emitting no barrier, which is what keeps the callback path as cheap as it is. The
    /// lifecycle transitions below are already ordered by the lock they run under, so their accesses need no mode stronger than that.
    private final AtomicBoolean startAttempted = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();

    @Override
    public final void start() {
        inLock(lifecycleStateLock, () -> {
            checkState(!startAttempted.getPlain(), "Component %s is already started", this);
            startAttempted.setOpaque(true);
            doStart();
            started.set(true);
        });
    }

    @Override
    public final void stop() {
        inLock(lifecycleStateLock, () -> {
            if (startAttempted.getPlain()) {
                startAttempted.setOpaque(false);
                started.set(false);
                doStop();
            }
        });
    }

    protected final boolean isStarted() {
        return started.get();
    }

    protected final boolean isStartedOpaque() {
        return started.getOpaque();
    }

    protected final boolean isStartedPlain() {
        return started.getPlain();
    }

    protected final void checkStarted() {
        checkState(isStarted(), "Component %s is not started or already stopped", this);
    }

    protected final void whenNotLifecycling(Runnable action) {
        inLock(lifecycleStateLock, action);
    }

    protected final void whenStartedAndNotLifecycling(Runnable action) {
        whenNotLifecycling(() -> {
            checkStarted();
            action.run();
        });
    }

    /// Runs `action` unless this component has stopped. This is the guard for a callback arriving from a producer this component subscribed to: the producer
    /// keeps firing until the unsubscribe takes effect, which can be after this component stopped. Where [#whenStartedAndNotLifecycling(Runnable)] throws,
    /// this returns quietly, because a producer cannot tell "you stopped" apart from a real fault.
    ///
    /// The admitted window opens at [#doStart()], so a value a producer delivers synchronously on subscription reaches the component.
    ///
    /// **Call this inside the submitted task, not around the submission.** Deciding on the component's own executor is what makes the verdict exact despite
    /// costing one opaque read and no lock: [#stop()] clears the flag before running [#doStop()], and the executor outlives that call, so a task reading
    /// `true` is running ahead of all teardown and anything it acquires will still be released. Deciding on the producer's thread would be a race, leaving
    /// `action` to run after teardown.
    ///
    /// The submission itself must use a form that discards the task once the executor is closed, so that a producer whose unsubscribe has yet to take effect
    /// is never handed a rejection on its own thread.
    protected final void ifNotStopped(Runnable action) {
        if (startAttempted.getOpaque()) {
            action.run();
        }
    }

    protected final <T> T whenNotLifecycling(Supplier<T> supplier) {
        return inLock(lifecycleStateLock, supplier);
    }

    protected final <T> T whenStartedAndNotLifecycling(Supplier<T> supplier) {
        return whenNotLifecycling(() -> {
            checkStarted();
            return supplier.get();
        });
    }

    protected void doStart() {
    }

    protected void doStop() {
    }
}
