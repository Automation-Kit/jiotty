package net.yudichev.jiotty.common.lang.throttling;

import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import net.yudichev.jiotty.common.lang.Closeable;

import java.time.Duration;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.closeIfNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.noop;

public final class ThrottlingConsumer<T> extends BaseIdempotentCloseable implements Consumer<T> {
    private static final Object NONE = new Object();
    private final SchedulingExecutor executor;
    private final Duration throttlingDuration;
    private final Consumer<T> delegate;

    private Object pendingValue = NONE;
    private boolean throttling;

    private Closeable throttlingTimerHandle = noop();

    public ThrottlingConsumer(SchedulingExecutor executor, Duration throttlingDuration, Consumer<T> delegate) {
        this.executor = checkNotNull(executor);
        checkArgument(!throttlingDuration.isNegative(), "throttlingDuration must not be negative, but was %s", throttlingDuration);
        this.throttlingDuration = throttlingDuration;
        this.delegate = checkNotNull(delegate);
    }

    @Override
    public void accept(T t) {
        executor.execute(() -> {
            if (!isClosedOpaque()) {
                pendingValue = t;
                if (!throttling) {
                    deliverValue();
                }
            }
        });
    }

    @Override
    protected void doClose() {
        // The delivery already queued on the
        // executor, or a timer about to fire, is suppressed when the executor drains it during teardown, after the components this delegate touches have
        // stopped. Cancelling the timer handle stays on the executor thread that mutates it.
        executor.execute(() -> closeIfNotNull(throttlingTimerHandle));
    }

    private void deliverValue() {
        assert pendingValue != NONE;
        if (isClosedOpaque()) {
            return;
        }
        //noinspection unchecked it's either T or NONE
        delegate.accept((T) pendingValue);
        pendingValue = NONE;

        if (!isClosedOpaque()) {
            throttlingTimerHandle = executor.schedule(throttlingDuration, this::onTimer);
            throttling = true;
        }
    }

    private void onTimer() {
        if (pendingValue == NONE) {
            throttling = false;
        } else {
            deliverValue();
        }
    }
}
