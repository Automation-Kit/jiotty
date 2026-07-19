package net.yudichev.jiotty.common.lang;

import org.apache.logging.log4j.Logger;

import java.util.function.BiConsumer;

import static com.google.common.base.Preconditions.checkNotNull;

public final class Runnables {
    private Runnables() {
    }

    public static Runnable guarded(Logger logger, String taskDescription, Runnable delegate) {
        return guarded(logger, taskDescription, delegate,
                       (desc, throwable) -> logger.error("Failed while {}", desc, throwable));
    }

    /// As [#guarded(Logger, String, Runnable)], but routes the task description and uncaught throwable to `exceptionHandler` instead of logging them — the
    /// handler decides how to report the failure. The handler runs inside its own guard, so if it throws, that secondary failure is logged and does not escape.
    public static Runnable guarded(Logger logger, String taskDescription, Runnable delegate, BiConsumer<String, Throwable> exceptionHandler) {
        return new GuardedRunnable(logger, taskDescription, delegate, exceptionHandler);
    }

    /// Runs `delegate` under the same guard as [#guarded(Logger, String, Runnable, BiConsumer)] but **without allocating a wrapper** — for a caller already on
    /// the execution thread that only needs the exception routing. An uncaught throwable goes to `exceptionHandler` (itself guarded: a secondary failure is
    /// logged and does not escape).
    public static void runGuarded(Logger logger, String taskDescription, Runnable delegate, BiConsumer<String, Throwable> exceptionHandler) {
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

    private record GuardedRunnable(Logger logger,
                                   String taskDescription,
                                   Runnable delegate,
                                   BiConsumer<String, Throwable> exceptionHandler) implements Runnable, StringFormattable {
        private GuardedRunnable {
            checkNotNull(logger);
            checkNotNull(delegate);
            checkNotNull(exceptionHandler);
        }

        @Override
        public void run() {
            runGuarded(logger, taskDescription, delegate, exceptionHandler);
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
