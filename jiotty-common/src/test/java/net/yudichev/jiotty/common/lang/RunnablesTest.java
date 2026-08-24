package net.yudichev.jiotty.common.lang;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RunnablesTest {
    @Mock
    private Runnable delegate;
    @Mock
    private BiConsumer<String, Throwable> exceptionHandler;

    @Test
    void successPathRunsDelegateAndDoesNotInvokeHandler() {
        Runnables.guarded("doing the thing", delegate, exceptionHandler).run();

        verify(delegate).run();
        verifyNoInteractions(exceptionHandler);
    }

    @Test
    void failurePathRoutesToHandler() {
        RuntimeException failure = new RuntimeException("boom");
        doThrow(failure).when(delegate).run();

        Runnables.guarded("doing the thing", delegate, exceptionHandler).run();

        verify(exceptionHandler).accept("doing the thing", failure);
    }

    @Test
    void throwingHandlerIsContainedAndDoesNotEscape() {
        RuntimeException failure = new RuntimeException("boom");
        doThrow(failure).when(delegate).run();
        doThrow(new RuntimeException("handler boom")).when(exceptionHandler).accept("doing the thing", failure);

        assertThatCode(() -> Runnables.guarded("doing the thing", delegate, exceptionHandler).run()).doesNotThrowAnyException();
    }

    @Test
    void runGuardedRunsDelegateOnSuccessPath() {
        Runnables.runGuarded("doing the thing", delegate, exceptionHandler);

        verify(delegate).run();
        verifyNoInteractions(exceptionHandler);
    }

    @Test
    void runGuardedRoutesFailureToHandler() {
        RuntimeException failure = new RuntimeException("boom");
        doThrow(failure).when(delegate).run();

        Runnables.runGuarded("doing the thing", delegate, exceptionHandler);

        verify(exceptionHandler).accept("doing the thing", failure);
    }
}
