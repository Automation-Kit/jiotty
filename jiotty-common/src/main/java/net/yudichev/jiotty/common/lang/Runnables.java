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
        checkNotNull(logger);
        checkNotNull(delegate);
        checkNotNull(exceptionHandler);
        return new Runnable() {
            @Override
            public void run() {
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

            @Override
            public String toString() {
                return "Task '" + taskDescription + "' -> " + delegate;
            }
        };
    }
}
