package net.yudichev.jiotty.common.inject;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkState;
import static net.yudichev.jiotty.common.lang.Locks.inLock;

@SuppressWarnings("AbstractClassWithoutAbstractMethods") // designed for extension
public abstract class BaseLifecycleComponent implements LifecycleComponent {
    private final Lock lifecycleStateLock = new ReentrantLock();
    private volatile boolean started;
    private boolean startAttempted;

    @Override
    public final void start() {
        inLock(lifecycleStateLock, () -> {
            checkState(!startAttempted, "Component %s is already started", this);
            startAttempted = true;
            doStart();
            started = true;
        });
    }

    @Override
    public final void stop() {
        inLock(lifecycleStateLock, () -> {
            if (startAttempted) {
                startAttempted = false;
                started = false;
                doStop();
            }
        });
    }

    protected final boolean isStarted() {
        return started;
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
