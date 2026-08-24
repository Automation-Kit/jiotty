package net.yudichev.jiotty.common.lang;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.BiConsumer;

import static com.google.common.base.Preconditions.checkNotNull;

public final class Runnables {
    /// Carries the last-resort line for an `exceptionHandler` that itself throws.
    private static final Logger logger = LogManager.getLogger(Runnables.class);

    private Runnables() {
    }

    /// Wraps `delegate` so an uncaught throwable goes to `exceptionHandler` along with the task description, and the handler decides how to report it. The
    /// handler runs inside its own guard, so a secondary failure in it is logged and contained.
    public static Runnable guarded(String taskDescription, Runnable delegate, BiConsumer<String, Throwable> exceptionHandler) {
        return new GuardedRunnable(taskDescription, delegate, exceptionHandler);
    }

    /// Runs `delegate` under the same guard as [#guarded], allocating no wrapper — for a caller already on the execution thread. An uncaught throwable goes
    /// to `exceptionHandler`, itself guarded as above.
    public static void runGuarded(String taskDescription, Runnable delegate, BiConsumer<String, Throwable> exceptionHandler) {
        try {
            delegate.run();
        } catch (Throwable e) {
            try {
                exceptionHandler.accept(taskDescription, e);
            } catch (Throwable t) {
                logger.error("Task exception handler failed while {}", taskDescription, t);
            }
        }
    }

    private record GuardedRunnable(String taskDescription,
                                   Runnable delegate,
                                   BiConsumer<String, Throwable> exceptionHandler) implements Runnable, StringFormattable {
        private GuardedRunnable {
            checkNotNull(taskDescription);
            checkNotNull(delegate);
            checkNotNull(exceptionHandler);
        }

        @Override
        public void run() {
            runGuarded(taskDescription, delegate, exceptionHandler);
        }

        @Override
        public String toString() {
            return toString(64);
        }

        @Override
        public void formatTo(Appendable appendable) {
            Append.to(appendable, "Task '");
            Append.to(appendable, taskDescription);
            Append.to(appendable, "' -> ");
            Append.to(appendable, delegate);
        }
    }
}
