package net.yudichev.jiotty.common.lang;

import com.google.common.base.Throwables;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;

public final class MoreThrowables {
    private MoreThrowables() {
    }

    /// @return the first link of `throwable`'s causal chain, `throwable` included, that is a `type`, or empty when none is
    /// @throws IllegalArgumentException if the causal chain loops before reaching a `type`, as [Throwables#getCausalChain] does
    public static <T extends Throwable> Optional<T> findInCausalChain(Throwable throwable, Class<T> type) {
        // Walks the chain in place, allocating nothing. The slow pointer, advancing at half speed, detects a chain that loops back on itself.
        Throwable slowPointer = throwable;
        boolean advanceSlowPointer = false;
        Throwable cause = throwable;
        while (!type.isInstance(cause)) {
            cause = cause.getCause();
            if (cause == null) {
                return Optional.empty();
            }
            checkArgument(cause != slowPointer, "Loop in causal chain detected.");
            if (advanceSlowPointer) {
                slowPointer = slowPointer.getCause();
            }
            advanceSlowPointer = !advanceSlowPointer;
        }
        return Optional.of(type.cast(cause));
    }

    public static void asUnchecked(CheckedExceptionThrowingRunnable action) {
        try {
            action.run();
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException(e);
        }
    }

    public static <T> T getAsUnchecked(CheckedExceptionThrowingSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @FunctionalInterface
    public interface CheckedExceptionThrowingRunnable {
        @SuppressWarnings("ProhibitedExceptionDeclared")
            // by design
        void run() throws Exception;
    }

    @FunctionalInterface
    public interface CheckedExceptionThrowingSupplier<T> {
        @SuppressWarnings("ProhibitedExceptionDeclared")
            // by design
        T get() throws Exception;
    }
}
