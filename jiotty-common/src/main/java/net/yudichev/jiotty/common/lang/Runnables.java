package net.yudichev.jiotty.common.lang;

import org.apache.logging.log4j.Logger;

import static com.google.common.base.Preconditions.checkNotNull;

public final class Runnables {
    private Runnables() {
    }

    public static Runnable guarded(Logger logger, String taskDescription, Runnable delegate) {
        checkNotNull(logger);
        checkNotNull(delegate);
        return new Runnable() {
            @Override
            public void run() {
                try {
                    delegate.run();
                } catch (Throwable e) {
                    logger.error("Failed while {}", taskDescription, e);
                }
            }

            @Override
            public String toString() {
                return "Task '" + taskDescription + "' -> " + delegate;
            }
        };
    }
}
