package net.yudichev.jiotty.common.lang;

import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.BiConsumer;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RunnablesTest {
    @Mock
    private Logger logger;
    @Mock
    private Runnable delegate;
    @Mock
    private BiConsumer<String, Throwable> exceptionHandler;

    @Test
    void successPathRunsDelegateAndNeitherLogsNorInvokesHandler() {
        Runnables.guarded(logger, "doing the thing", delegate, exceptionHandler).run();

        verify(delegate).run();
        verifyNoInteractions(exceptionHandler);
        verifyNoInteractions(logger);
    }

    @Test
    void failurePathRoutesToHandlerWithoutLogging() {
        RuntimeException failure = new RuntimeException("boom");
        doThrow(failure).when(delegate).run();

        Runnables.guarded(logger, "doing the thing", delegate, exceptionHandler).run();

        verify(exceptionHandler).accept("doing the thing", failure);
        verifyNoInteractions(logger);
    }

    @Test
    void throwingHandlerIsSwallowedAndLogged() {
        RuntimeException failure = new RuntimeException("boom");
        RuntimeException handlerFailure = new RuntimeException("handler boom");
        doThrow(failure).when(delegate).run();
        doThrow(handlerFailure).when(exceptionHandler).accept("doing the thing", failure);

        Runnables.guarded(logger, "doing the thing", delegate, exceptionHandler).run();

        verify(logger).error("Task exception handler failed while {}", "doing the thing", handlerFailure);
    }

    @Test
    void overloadWithoutHandlerStillLogsFailure() {
        RuntimeException failure = new RuntimeException("boom");
        doThrow(failure).when(delegate).run();

        Runnables.guarded(logger, "doing the thing", delegate).run();

        verify(logger).error("Failed while {}", "doing the thing", failure);
    }
}
