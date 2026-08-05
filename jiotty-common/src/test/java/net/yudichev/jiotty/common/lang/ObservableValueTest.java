package net.yudichev.jiotty.common.lang;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class ObservableValueTest {
    @ParameterizedTest
    @MethodSource("impls")
    void scenario(ObservableValue<Integer> value, @Mock Consumer<Integer> consumer1, @Mock Consumer<Integer> consumer2) {
        assertThat(value.toString()).isEqualTo("0");

        int val = 0;
        for (int i = 0; i < 2; i++) {
            var sub1 = value.subscribe(consumer1);
            verify(consumer1).accept(val);

            value.accept(++val);
            verify(consumer1).accept(val);

            var sub2 = value.subscribe(consumer2);
            verifyNoMoreInteractions(consumer1);
            verify(consumer2).accept(val);

            value.accept(++val);
            verify(consumer1).accept(val);
            verify(consumer2).accept(val);

            if (value instanceof SimpleObservableValue) {
                value.setNotificationsSuppressed(true);
                value.accept(++val);
                verifyNoMoreInteractions(consumer1, consumer2);

                value.setNotificationsSuppressed(false);
                verify(consumer1).accept(val);
                verify(consumer2).accept(val);
            }

            value.accept(++val);
            verify(consumer1).accept(val);
            verify(consumer2).accept(val);

            sub1.close();
            sub1.close();

            value.accept(++val);
            verifyNoMoreInteractions(consumer1);
            verify(consumer2).accept(val);

            if (value instanceof SimpleObservableValue) {
                // subscribe when notifications are suppressed
                value.setNotificationsSuppressed(true);
                value.accept(++val);
                sub1 = value.subscribe(consumer1);
                verifyNoMoreInteractions(consumer1, consumer2);

                value.setNotificationsSuppressed(false);
                verify(consumer1).accept(val);
                verify(consumer2).accept(val);
            }

            sub1.close();
            sub2.close();

            value.accept(++val);
            verifyNoMoreInteractions(consumer1, consumer2);
        }
    }

    @Test
    void givenSubscribedAfterNotificationsSilenced_whenUnsuppressed_thenInitialValueReceived(@Mock Consumer<Integer> consumer) {
        ObservableValue<Integer> value = ObservableValue.simple(0);
        value.setNotificationsSuppressed(true);
        value.subscribe(consumer);
        verifyNoMoreInteractions(consumer);
        value.setNotificationsSuppressed(false);
        verify(consumer).accept(0);
    }

    @Test
    void givenSubscribedOnExecutor_whenValueChanges_thenCurrentAndLaterValuesDeliveredOnThatExecutor(@Mock Consumer<Integer> consumer) {
        var clock = new ProgrammableClock();
        var executor = clock.createSingleThreadedSchedulingExecutor("test");
        ObservableValue<Integer> value = ObservableValue.simple(0);

        value.subscribe(executor, consumer);
        verifyNoMoreInteractions(consumer);

        clock.tick();
        verify(consumer).accept(0);

        value.accept(1);
        verify(consumer).accept(1);
    }

    @Test
    void givenSubscribedOnExecutor_whenSubscriptionClosed_thenNoFurtherValuesDelivered(@Mock Consumer<Integer> consumer) {
        var clock = new ProgrammableClock();
        var executor = clock.createSingleThreadedSchedulingExecutor("test");
        ObservableValue<Integer> value = ObservableValue.simple(0);
        Closeable subscription = value.subscribe(executor, consumer);
        clock.tick();

        subscription.close();
        clock.tick();
        value.accept(1);

        verify(consumer).accept(0);
        verifyNoMoreInteractions(consumer);
    }

    /// Teardown drains executors last, so a subscriber routinely releases its handle after the observable's owner has stopped.
    @Test
    void givenExecutorClosed_whenSubscriptionClosed_thenNoFailure(@Mock Consumer<Integer> consumer) {
        var clock = new ProgrammableClock();
        var executor = clock.createSingleThreadedSchedulingExecutor("test");
        ObservableValue<Integer> value = ObservableValue.simple(0);
        Closeable subscription = value.subscribe(executor, consumer);
        clock.tick();

        executor.close();

        assertThatCode(subscription::close).doesNotThrowAnyException();
    }

    public static Stream<ObservableValue<Integer>> impls() {
        return Stream.of(ObservableValue.simple(0), ObservableValue.concurrent(0));
    }
}