package net.yudichev.jiotty.common.lang.throttling;

import org.apache.logging.log4j.Logger;

import static com.google.common.base.Preconditions.checkNotNull;

public final class ThresholdExceptionLoggingRunnable implements Runnable {
    private final Runnable delegate;
    private final ThresholdGatedConsumer<RuntimeException> exceptionLoggingAction;

    private ThresholdExceptionLoggingRunnable(Runnable delegate, ThresholdGatedConsumer<RuntimeException> exceptionLoggingAction) {
        this.delegate = checkNotNull(delegate);
        this.exceptionLoggingAction = checkNotNull(exceptionLoggingAction);
    }

    public static Runnable withExceptionLoggedAfterThreshold(Logger logger, String description, int threshold, Runnable delegate) {
        return new ThresholdExceptionLoggingRunnable(delegate,
                                                     ThresholdGatedConsumer.thresholdGated(threshold, e -> logger.error("Error when {}", description, e)));
    }

    @Override
    public void run() {
        try {
            delegate.run();
            exceptionLoggingAction.reset();
        } catch (RuntimeException e) {
            exceptionLoggingAction.accept(e);
        }
    }
}
