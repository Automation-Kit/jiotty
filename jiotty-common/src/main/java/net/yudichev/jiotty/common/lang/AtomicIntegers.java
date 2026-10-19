package net.yudichev.jiotty.common.lang;

import java.util.concurrent.atomic.AtomicInteger;

/// Static methods over [AtomicInteger] values.
public final class AtomicIntegers {
    private AtomicIntegers() {
    }

    /// Increments `counter` unless it has reached `limit`, by compare-and-set, so concurrent callers at the limit cannot both increment it.
    ///
    /// @return `true` when `counter` was incremented; `false` when it was at or above `limit`
    public static boolean tryIncrementBelow(AtomicInteger counter, int limit) {
        int current;
        do {
            current = counter.get();
            if (current >= limit) {
                return false;
            }
        } while (!counter.compareAndSet(current, current + 1));
        return true;
    }
}
