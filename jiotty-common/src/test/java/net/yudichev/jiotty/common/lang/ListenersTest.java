package net.yudichev.jiotty.common.lang;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class ListenersTest {

    private final Listeners<Integer> listeners = new Listeners<>();
    private final ProgrammableClock clock = new ProgrammableClock();

    @Test
    void givenListenerAddedOnExecutor_whenNotified_thenImageAndLaterValuesDeliveredOnThatExecutor(@Mock Consumer<Integer> consumer) {
        var executor = clock.createSingleThreadedSchedulingExecutor("test");

        listeners.addListener(executor, () -> Optional.of(0), consumer);
        verifyNoMoreInteractions(consumer);

        clock.tick();
        verify(consumer).accept(0);

        listeners.notify(1);
        verify(consumer).accept(1);
    }

    @Test
    void givenNoImageAvailable_whenListenerAddedOnExecutor_thenOnlyLaterValuesDelivered(@Mock Consumer<Integer> consumer) {
        var executor = clock.createSingleThreadedSchedulingExecutor("test");

        listeners.addListener(executor, Optional::empty, consumer);
        clock.tick();
        verifyNoMoreInteractions(consumer);

        listeners.notify(1);
        verify(consumer).accept(1);
    }

    @Test
    void givenListenerAddedOnExecutor_whenHandleClosed_thenNoFurtherValuesDelivered(@Mock Consumer<Integer> consumer) {
        var executor = clock.createSingleThreadedSchedulingExecutor("test");
        Closeable handle = listeners.addListener(executor, () -> Optional.of(0), consumer);
        clock.tick();

        handle.close();
        clock.tick();
        listeners.notify(1);

        verify(consumer).accept(0);
        verifyNoMoreInteractions(consumer);
    }

    /// Teardown drains executors last, so a listener routinely releases its handle after the executor it subscribed on has closed.
    @Test
    void givenExecutorClosed_whenHandleClosed_thenNoFailure(@Mock Consumer<Integer> consumer) {
        var executor = clock.createSingleThreadedSchedulingExecutor("test");
        Closeable handle = listeners.addListener(executor, () -> Optional.of(0), consumer);
        clock.tick();

        executor.close();

        assertThatCode(handle::close).doesNotThrowAnyException();
    }
}
