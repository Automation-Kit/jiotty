package net.yudichev.jiotty.common.observer;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.RejectingSchedulingExecutor;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.CompositeException;
import net.yudichev.jiotty.common.lang.MutableReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class ObservableValueTest {
    private final ProgrammableClock clock = new ProgrammableClock();
    private final SchedulingExecutor executor = clock.createSingleThreadedSchedulingExecutor("test");

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

    @ParameterizedTest
    @MethodSource("impls")
    void givenSubscribedOnExecutor_whenValueChanges_thenCurrentAndLaterValuesDeliveredOnThatExecutor(ObservableValue<Integer> value,
                                                                                                     @Mock Consumer<Integer> consumer) {
        value.subscribe(executor, consumer);
        verifyNoMoreInteractions(consumer);

        clock.tick();
        verify(consumer).accept(0);

        value.accept(1);
        clock.tick();
        verify(consumer).accept(1);
    }

    @ParameterizedTest
    @MethodSource("impls")
    void givenSubscribedOnExecutor_whenSubscriptionClosed_thenNoFurtherValuesDelivered(ObservableValue<Integer> value, @Mock Consumer<Integer> consumer) {
        Closeable subscription = value.subscribe(executor, consumer);
        clock.tick();

        subscription.close();
        clock.tick();
        value.accept(1);
        clock.tick();

        verify(consumer).accept(0);
        verifyNoMoreInteractions(consumer);
    }

    /// Teardown drains executors last, so a subscriber routinely releases its handle after the observable's owner has stopped.
    @ParameterizedTest
    @MethodSource("impls")
    void givenExecutorClosed_whenSubscriptionClosed_thenNoFailure(ObservableValue<Integer> value, @Mock Consumer<Integer> consumer) {
        Closeable subscription = value.subscribe(executor, consumer);
        clock.tick();

        executor.close();

        assertThatCode(subscription::close).doesNotThrowAnyException();
    }

    @Test
    void givenExecutorQueueFull_whenSimpleValueSubscriptionClosed_thenTheRejectionIsThrown(@Mock Consumer<Integer> consumer) {
        ObservableValue<Integer> value = ObservableValue.simple(0);
        var rejectingExecutor = new RejectingSchedulingExecutor(executor);
        Closeable subscription = value.subscribe(rejectingExecutor, consumer);
        clock.tick();
        rejectingExecutor.fillQueue();

        assertThatThrownBy(subscription::close).isInstanceOf(RejectedExecutionException.class);
    }

    @ParameterizedTest
    @MethodSource("impls")
    void givenSubscriptionClosedBeforeTheExecutorRuns_whenAValueChanges_thenTheValueIsNotDelivered(ObservableValue<Integer> value,
                                                                                                   @Mock Consumer<Integer> consumer) {
        Closeable subscription = value.subscribe(executor, consumer);

        subscription.close();
        clock.tick();
        value.accept(1);
        clock.tick();

        verify(consumer, never()).accept(1);
        assertThat(value.subscriberCount()).isZero();
    }

    @Test
    void givenSimpleValueSubscriptionClosedBeforeTheExecutorRuns_whenTheExecutorRuns_thenNothingIsDelivered(@Mock Consumer<Integer> consumer) {
        ObservableValue<Integer> value = ObservableValue.simple(0);
        Closeable subscription = value.subscribe(executor, consumer);

        subscription.close();
        clock.tick();

        verifyNoMoreInteractions(consumer);
    }

    /// The task that subscribes throws out of [ProgrammableClock#tick()], which is how that clock's executor reports a failed task.
    @Test
    void givenSubscribedOnExecutor_whenListenerThrowsOnTheCurrentValue_thenTickThrowsAndLaterValuesAreDelivered() {
        ObservableValue<Integer> value = ObservableValue.simple(0);
        var receivedValues = new ArrayList<Integer>();
        var failure = new RuntimeException("current value boom");
        value.subscribe(executor, newValue -> {
            if (newValue == 0) {
                throw failure;
            }
            receivedValues.add(newValue);
        });

        assertThatThrownBy(clock::tick).isSameAs(failure);
        value.accept(1);

        assertThat(receivedValues).containsExactly(1);
    }

    @Test
    void givenSubscribedOnExecutor_whenListenerThatThrewOnTheCurrentValueIsClosed_thenNoFurtherValuesDelivered() {
        ObservableValue<Integer> value = ObservableValue.simple(0);
        var receivedValues = new ArrayList<Integer>();
        var failure = new RuntimeException("current value boom");
        Closeable subscription = value.subscribe(executor, newValue -> {
            receivedValues.add(newValue);
            if (newValue == 0) {
                throw failure;
            }
        });
        assertThatThrownBy(clock::tick).isSameAs(failure);

        subscription.close();
        clock.tick();
        value.accept(1);

        assertThat(receivedValues).containsExactly(0);
        assertThat(value.subscriberCount()).isZero();
    }

    @Test
    void givenSubscribedOnExecutorWhileSuppressed_whenUnsuppressed_thenInitialValueReceived(@Mock Consumer<Integer> consumer) {
        ObservableValue<Integer> value = ObservableValue.simple(0);
        value.setNotificationsSuppressed(true);

        value.subscribe(executor, consumer);
        clock.tick();
        verifyNoMoreInteractions(consumer);

        value.setNotificationsSuppressed(false);
        verify(consumer).accept(0);
    }

    @Test
    void aSimpleValueKeepsAListenerThatThrowsOnTheCurrentValueWhenSubscribedDirectly() {
        ObservableValue<Integer> value = ObservableValue.simple(0);
        var receivedValues = new ArrayList<Integer>();
        var failure = new RuntimeException("current value boom");

        assertThatThrownBy(() -> value.subscribe(newValue -> {
            receivedValues.add(newValue);
            if (newValue == 0) {
                throw failure;
            }
        })).isSameAs(failure);
        value.accept(1);

        assertThat(receivedValues).containsExactly(0, 1);
    }

    @Test
    void aSimpleValueDeliversToANewListenerTheValueThatItPassesToAcceptWhileReceivingTheCurrentValue() {
        ObservableValue<Integer> value = ObservableValue.simple(0);
        var receivedValues = new ArrayList<Integer>();

        value.subscribe(newValue -> {
            receivedValues.add(newValue);
            if (newValue == 0) {
                value.accept(1);
            }
        });

        assertThat(receivedValues).containsExactly(0, 1);
    }

    @ParameterizedTest
    @MethodSource("impls")
    void aListenerMayCloseItsOwnSubscriptionWhileItIsNotified(ObservableValue<Integer> value, @Mock Consumer<Integer> otherListener) {
        var receivedValues = new ArrayList<Integer>();
        var subscription = new MutableReference<Closeable>();
        subscription.set(value.subscribe(newValue -> {
            receivedValues.add(newValue);
            if (newValue == 1) {
                subscription.get().close();
            }
        }));
        value.subscribe(otherListener);

        value.accept(1);
        value.accept(2);

        assertThat(receivedValues).containsExactly(0, 1);
        verify(otherListener).accept(1);
        verify(otherListener).accept(2);
        assertThat(value.subscriberCount()).isOne();
    }

    @Test
    void aSimpleValueDeliversNothingMoreToAListenerWhoseSubscriptionAnEarlierListenerClosesWhileItIsNotified(@Mock Consumer<Integer> laterListener) {
        ObservableValue<Integer> value = ObservableValue.simple(0);
        var laterSubscription = new MutableReference<Closeable>();
        value.subscribe(newValue -> {
            if (newValue == 1) {
                laterSubscription.get().close();
            }
        });
        laterSubscription.set(value.subscribe(laterListener));

        value.accept(1);
        value.accept(2);

        verify(laterListener).accept(0);
        verifyNoMoreInteractions(laterListener);
        assertThat(value.subscriberCount()).isOne();
    }

    @ParameterizedTest
    @MethodSource("impls")
    void aListenerSubscribedWhileAnotherIsNotifiedReceivesTheValueOnceAndThenLaterValues(ObservableValue<Integer> value) {
        var receivedValues = new ArrayList<Integer>();
        value.subscribe(newValue -> {
            if (newValue == 1) {
                value.subscribe(receivedValues::add);
            }
        });

        value.accept(1);
        value.accept(2);

        assertThat(receivedValues).containsExactly(1, 2);
    }

    @Test
    void aSimpleValueListenerMayCloseItsOwnSubscriptionWhenNotificationsResume() {
        ObservableValue<Integer> value = ObservableValue.simple(0);
        var receivedValues = new ArrayList<Integer>();
        var otherReceivedValues = new ArrayList<Integer>();
        var subscription = new MutableReference<Closeable>();
        subscription.set(value.subscribe(newValue -> {
            receivedValues.add(newValue);
            if (newValue == 1) {
                subscription.get().close();
            }
        }));
        value.subscribe(otherReceivedValues::add);
        value.setNotificationsSuppressed(true);
        value.accept(1);

        value.setNotificationsSuppressed(false);
        value.accept(2);

        assertThat(receivedValues).containsExactly(0, 1);
        assertThat(otherReceivedValues).containsExactly(0, 1, 2);
        assertThat(value.subscriberCount()).isOne();
    }

    @Test
    void aSimpleValueDeliversAValueToEveryListenerBeforeThrowingTheExceptionsOfSeveral() {
        ObservableValue<Integer> value = ObservableValue.simple(0);
        var receivedValues = new ArrayList<Integer>();
        value.subscribe(newValue -> {
            if (newValue == 1) {
                throw new IllegalStateException("first");
            }
        });
        value.subscribe(newValue -> {
            if (newValue == 1) {
                throw new IllegalStateException("second");
            }
        });
        value.subscribe(receivedValues::add);

        assertThatThrownBy(() -> value.accept(1)).isInstanceOf(CompositeException.class).hasMessage("first; second");
        assertThat(receivedValues).containsExactly(0, 1);
    }

    @Test
    void aConcurrentValueHandsWhatAnObserverThrowsToTheHandlerAndKeepsDelivering(@Mock Consumer<Integer> consumer) {
        var failures = new ArrayList<RuntimeException>();
        ObservableValue<Integer> value = ObservableValue.concurrent(0, failures::add);
        var rejection = new RejectedExecutionException("queue full");
        value.subscribe(newValue -> {
            if (newValue == 1) {
                throw rejection;
            }
        });
        value.subscribe(consumer);

        value.accept(1);
        value.accept(2);

        assertThat(failures).containsExactly(rejection);
        verify(consumer).accept(1);
        verify(consumer).accept(2);
    }

    @ParameterizedTest
    @MethodSource("impls")
    void aValuePassedToAcceptByANotifiedListenerReachesEveryListenerAfterTheValueBeingDelivered(ObservableValue<Integer> value) {
        var firstReceivedValues = new ArrayList<Integer>();
        var secondReceivedValues = new ArrayList<Integer>();
        value.subscribe(newValue -> {
            firstReceivedValues.add(newValue);
            if (newValue == 1) {
                value.accept(2);
            }
        });
        value.subscribe(secondReceivedValues::add);

        value.accept(1);

        assertThat(firstReceivedValues).containsExactly(0, 1, 2);
        assertThat(secondReceivedValues).containsExactly(0, 1, 2);
        assertThat(value.get()).isEqualTo(2);
    }

    @ParameterizedTest
    @MethodSource("impls")
    void valuesThatNotifiedListenersPassToAcceptReachEveryListenerInTheOrderOfTheCalls(ObservableValue<Integer> value) {
        var receivedValues = new ArrayList<Integer>();
        value.subscribe(newValue -> {
            if (newValue == 1) {
                value.accept(2);
                value.accept(3);
            }
        });
        value.subscribe(receivedValues::add);

        value.accept(1);
        value.accept(4);

        assertThat(receivedValues).containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    void aSimpleValueCountsNoListenerThatAnotherListenerUnsubscribedDuringTheNotification() {
        ObservableValue<Integer> value = ObservableValue.simple(0);
        var laterSubscription = new MutableReference<Closeable>();
        var countsDuringNotification = new ArrayList<Integer>();
        value.subscribe(newValue -> {
            if (newValue == 1) {
                laterSubscription.get().close();
                countsDuringNotification.add(value.subscriberCount());
            }
        });
        laterSubscription.set(value.subscribe(_ -> {}));

        value.accept(1);

        assertThat(countsDuringNotification).containsExactly(1);
        assertThat(value.subscriberCount()).isOne();
    }

    @Test
    void aConcurrentValueCallsAnExecutorSubscriberOnlyOnItsExecutor(@Mock Consumer<Integer> consumer) {
        ObservableValue<Integer> value = TestObservableValues.concurrent(0);
        value.subscribe(executor, consumer);

        value.accept(1);
        verifyNoMoreInteractions(consumer);

        clock.tick();
        var inOrder = inOrder(consumer);
        inOrder.verify(consumer).accept(0);
        inOrder.verify(consumer).accept(1);
    }

    /// The task that delivers throws out of [ProgrammableClock#tick()], which is how that clock's executor reports a failed task.
    @Test
    void aConcurrentValuePassesWhatAnExecutorSubscriberThrowsToTheExecutorAndKeepsDelivering() {
        ObservableValue<Integer> value = TestObservableValues.concurrent(0);
        var receivedValues = new ArrayList<Integer>();
        var failure = new RuntimeException("delivery boom");
        value.subscribe(executor, newValue -> {
            if (newValue == 1) {
                throw failure;
            }
            receivedValues.add(newValue);
        });
        clock.tick();

        value.accept(1);
        assertThatThrownBy(clock::tick).isSameAs(failure);
        value.accept(2);
        clock.tick();

        assertThat(receivedValues).containsExactly(0, 2);
    }

    public static Stream<ObservableValue<Integer>> impls() {
        return Stream.of(ObservableValue.simple(0), TestObservableValues.concurrent(0));
    }
}