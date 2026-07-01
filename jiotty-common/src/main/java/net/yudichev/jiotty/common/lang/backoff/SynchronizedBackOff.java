package net.yudichev.jiotty.common.lang.backoff;

import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.StringFormattable;

import static com.google.common.base.Preconditions.checkNotNull;

public final class SynchronizedBackOff implements BackOff, StringFormattable {
    private final BackOff delegate;
    private final Object lock = new Object();

    public SynchronizedBackOff(BackOff delegate) {
        this.delegate = checkNotNull(delegate);
    }

    @Override
    public void reset() {
        synchronized (lock) {
            delegate.reset();
        }
    }

    @Override
    public long nextBackOffMillis() {
        synchronized (lock) {
            return delegate.nextBackOffMillis();
        }
    }

    @Override
    public long getMaxElapsedTimeMillis() {
        synchronized (lock) {
            return delegate.getMaxElapsedTimeMillis();
        }
    }

    @Override
    public String toString() {
        return toString(64);
    }

    @Override
    public void formatTo(Appendable appendable) {
        Append.to(appendable, "SynchronizedBackOff{delegate=");
        Append.to(appendable, delegate);
        Append.to(appendable, '}');
    }
}
