package net.yudichev.jiotty.common.async;

import net.yudichev.jiotty.common.lang.Closeable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ListenerBackedTaskExceptionHandlerRegistryTest {
    @Mock
    private BiConsumer<String, Throwable> handler;
    @Mock
    private BiConsumer<String, Throwable> otherHandler;
    private ListenerBackedTaskExceptionHandlerRegistry taskExceptionHandler;

    @BeforeEach
    void setUp() {
        taskExceptionHandler = new ListenerBackedTaskExceptionHandlerRegistry();
    }

    @Test
    void registeredHandlerReceivesTaskDescriptionAndException() {
        var failure = new RuntimeException("boom");
        taskExceptionHandler.addExceptionHandler(handler);

        taskExceptionHandler.onTaskException("doing the thing", failure);

        verify(handler).accept("doing the thing", failure);
    }

    @Test
    void rejectsNullHandler() {
        assertThatNullPointerException().isThrownBy(() -> taskExceptionHandler.addExceptionHandler(null));
    }

    @Test
    void closingRegistrationStopsNotifications() {
        Closeable registration = taskExceptionHandler.addExceptionHandler(handler);
        registration.close();

        taskExceptionHandler.onTaskException("doing the thing", new RuntimeException("boom"));

        verifyNoInteractions(handler);
    }

    @Test
    void notifiesEveryRegisteredHandler() {
        var failure = new RuntimeException("boom");
        taskExceptionHandler.addExceptionHandler(handler);
        taskExceptionHandler.addExceptionHandler(otherHandler);

        taskExceptionHandler.onTaskException("doing the thing", failure);

        verify(handler).accept("doing the thing", failure);
        verify(otherHandler).accept("doing the thing", failure);
    }
}
