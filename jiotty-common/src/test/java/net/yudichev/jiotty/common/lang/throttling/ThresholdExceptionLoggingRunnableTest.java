package net.yudichev.jiotty.common.lang.throttling;

import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ThresholdExceptionLoggingRunnableTest {
    @Mock
    private Runnable delegate;
    @SuppressWarnings("NonConstantLogger")
    @Mock
    private Logger logger;
    private Runnable runnable;

    @BeforeEach
    void setUp() {
        runnable = ThresholdExceptionLoggingRunnable.withExceptionLoggedAfterThreshold(logger, "description", 2, delegate);
    }

    @Test
    void logsSubsequentErrorsOnlyAfterThreshold() {
        RuntimeException exception = new RuntimeException("oops");
        doThrow(exception).when(delegate).run();

        runnable.run();
        verifyNoInteractions(logger);

        runnable.run();
        verifyNoInteractions(logger);

        runnable.run();
        verify(logger).error(any(String.class), contains("description"), eq(exception));
    }

    @Test
    void resetsErrorCounterIfNoException() {
        RuntimeException exception = new RuntimeException("oops");
        doThrow(exception)
                .doThrow(exception)
                .doNothing()
                .doThrow(exception)
                .when(delegate).run();

        runnable.run();
        runnable.run();
        runnable.run();
        runnable.run();
        verifyNoInteractions(logger);
    }
}