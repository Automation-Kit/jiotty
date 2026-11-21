package net.yudichev.jiotty.common.observer;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.RejectingSchedulingExecutor;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class ListenersTest {
    private final Listeners<Integer> listeners = new Listeners<>();
    private final ProgrammableClock clock = new ProgrammableClock();
    private final SchedulingExecutor executor = clock.createSingleThreadedSchedulingExecutor("test");

    @Test
    void givenListenerAddedOnExecutor_whenNotified_thenImageAndLaterValuesDeliveredOnThatExecutor(@Mock Consumer<Integer> consumer) {
        listeners.addListener(executor, () -> Optional.of(0), consumer);
        verifyNoMoreInteractions(consumer);

        clock.tick();
        verify(consumer).accept(0);

        listeners.notify(1);
        verify(consumer).accept(1);
    }

    @Test
    void givenNoImageAvailable_whenListenerAddedOnExecutor_thenOnlyLaterValuesDelivered(@Mock Consumer<Integer> consumer) {
        listeners.addListener(executor, Optional::empty, consumer);
        clock.tick();
        verifyNoMoreInteractions(consumer);

        listeners.notify(1);
        verify(consumer).accept(1);
    }

    @Test
    void givenListenerAddedOnExecutor_whenHandleClosed_thenNoFurtherValuesDelivered(@Mock Consumer<Integer> consumer) {
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
        Closeable handle = listeners.addListener(executor, () -> Optional.of(0), consumer);
        clock.tick();

        executor.close();

        assertThatCode(handle::close).doesNotThrowAnyException();
    }

    @Test
    void givenExecutorQueueFull_whenHandleClosed_thenTheRejectionIsThrown(@Mock Consumer<Integer> consumer) {
        var rejectingExecutor = new RejectingSchedulingExecutor(executor);
        Closeable handle = listeners.addListener(rejectingExecutor, () -> Optional.of(0), consumer);
        clock.tick();
        rejectingExecutor.fillQueue();

        assertThatThrownBy(handle::close).isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    void givenImageSupplierThrows_whenListenerAddedOnExecutor_thenTheTaskFailsAndLaterValuesNotDelivered(@Mock Consumer<Integer> consumer) {
        var failure = new RuntimeException("unreadable");

        listeners.addListener(executor, () -> {throw failure;}, consumer);

        assertThatThrownBy(clock::tick).isSameAs(failure);
        listeners.notify(1);
        verifyNoInteractions(consumer);
        assertThat(listeners.isEmpty()).isTrue();
    }

    @Test
    void givenConsumerThrowsOnTheImage_whenListenerAddedOnExecutor_thenTheTaskFailsAndLaterValuesDelivered(@Mock Consumer<Integer> consumer) {
        var failure = new RuntimeException("boom");
        doThrow(failure).when(consumer).accept(0);

        listeners.addListener(executor, () -> Optional.of(0), consumer);

        assertThatThrownBy(clock::tick).isSameAs(failure);
        listeners.notify(1);
        verify(consumer).accept(1);
    }

    @Test
    void givenHandleClosedBeforeTheListenerIsAdded_whenNotified_thenNothingIsDelivered(@Mock Consumer<Integer> consumer) {
        Closeable handle = listeners.addListener(executor, () -> Optional.of(0), consumer);

        handle.close();
        clock.tick();
        listeners.notify(1);

        verifyNoInteractions(consumer);
        assertThat(listeners.isEmpty()).isTrue();
    }
}
