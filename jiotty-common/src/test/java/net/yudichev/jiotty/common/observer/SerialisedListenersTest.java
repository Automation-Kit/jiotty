package net.yudichev.jiotty.common.observer;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.MutableReference;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static net.yudichev.jiotty.common.async.EvenMoreExecutors.directExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SerialisedListenersTest {
    private final ProgrammableClock clock = new ProgrammableClock();
    private final SchedulingExecutor executor = clock.createSingleThreadedSchedulingExecutor("publisher");
    private final SerialisedListeners<List<String>> listeners = new SerialisedListeners<>();

    @Test
    void deliversTheImageThenEveryNotification() {
        var receivedNotifications = new ArrayList<String>();

        listeners.subscribe(executor, receivedNotifications, listener -> listener.add("image"), rethrowing());
        clock.tick();
        listeners.publish(listener -> listener.add("first"), rethrowing());
        listeners.publish(listener -> listener.add("second"), rethrowing());

        assertThat(receivedNotifications).containsExactly("image", "first", "second");
    }

    @Test
    void aSubscriptionClosedBeforeItIsAddedReceivesNothing() {
        var receivedNotifications = new ArrayList<String>();
        Closeable subscription = listeners.subscribe(executor, receivedNotifications, listener -> listener.add("image"), rethrowing());

        subscription.close();
        clock.tick();
        listeners.publish(listener -> listener.add("change"), rethrowing());

        assertThat(receivedNotifications).isEmpty();
        assertThat(listeners.size()).isZero();
    }

    @Test
    void aListenerThatThrowsOnItsImageIsReportedAndLeftOut() {
        var receivedNotifications = new ArrayList<String>();
        var failures = new ArrayList<String>();

        listeners.subscribe(executor,
                            receivedNotifications,
                            _ -> {throw new RuntimeException("image boom");},
                            (_, e) -> failures.add(e.getMessage()));
        clock.tick();
        listeners.publish(listener -> listener.add("change"), rethrowing());

        assertThat(failures).containsExactly("image boom");
        assertThat(receivedNotifications).isEmpty();
        assertThat(listeners.size()).isZero();
    }

    /// A listener that throws on a notification may have missed the notification, so the listener is removed and receives no later notification.
    @Test
    void aThrowingListenerIsReportedDroppedAndLeavesTheOthersNotified() {
        var failingListener = new ArrayList<String>();
        var healthyListener = new ArrayList<String>();
        listeners.subscribe(executor, failingListener, _ -> {}, rethrowing());
        listeners.subscribe(executor, healthyListener, _ -> {}, rethrowing());
        clock.tick();
        var failures = new ArrayList<List<String>>();

        listeners.publish(listener -> {
            if (listener == failingListener) {
                throw new RuntimeException("boom");
            }
            listener.add("first");
        }, (listener, _) -> failures.add(listener));
        listeners.publish(listener -> listener.add("second"), rethrowing());

        assertThat(failures).containsExactly(failingListener);
        assertThat(failingListener).isEmpty();
        assertThat(healthyListener).containsExactly("first", "second");
        assertThat(listeners.size()).isOne();
    }

    /// Closing queues the removal on the executor; until it runs, the next notification skips and drops the closed subscription itself.
    @Test
    void aClosedSubscriptionIsNotifiedNoMore() {
        var receivedNotifications = new ArrayList<String>();
        Closeable subscription = listeners.subscribe(executor, receivedNotifications, _ -> {}, rethrowing());
        clock.tick();

        subscription.close();
        listeners.publish(listener -> listener.add("change"), rethrowing());
        clock.tick();

        assertThat(receivedNotifications).isEmpty();
        assertThat(listeners.size()).isZero();
    }

    @Test
    void closingASubscriptionRemovesItInATaskOnItsExecutor() {
        Closeable subscription = listeners.subscribe(executor, new ArrayList<>(), _ -> {}, rethrowing());
        clock.tick();

        subscription.close();
        assertThat(listeners.size()).isOne();
        clock.tick();

        assertThat(listeners.size()).isZero();
    }

    @Test
    void closingASubscriptionAfterItsExecutorShutDownDoesNotThrow() {
        Closeable subscription = listeners.subscribe(executor, new ArrayList<>(), _ -> {}, rethrowing());
        clock.tick();

        executor.close();

        assertThatCode(subscription::close).doesNotThrowAnyException();
    }

    @Test
    void removingAllDeliversALastNotificationToEachOpenListenerAndNotifiesNoneAfterwards() {
        var openListener = new ArrayList<String>();
        var closedListener = new ArrayList<String>();
        listeners.subscribe(executor, openListener, _ -> {}, rethrowing());
        Closeable closedSubscription = listeners.subscribe(executor, closedListener, _ -> {}, rethrowing());
        clock.tick();
        closedSubscription.close();

        listeners.removeAll(listener -> listener.add("last"), rethrowing());
        listeners.publish(listener -> listener.add("change"), rethrowing());

        assertThat(openListener).containsExactly("last");
        assertThat(closedListener).isEmpty();
        assertThat(listeners.size()).isZero();
    }

    @Test
    void aListenerThatThrowsOnItsLastNotificationIsReportedAndTheOthersReceiveItAndAreRemoved() {
        var failingListener = new ArrayList<String>();
        var healthyListener = new ArrayList<String>();
        listeners.subscribe(executor, failingListener, _ -> {}, rethrowing());
        listeners.subscribe(executor, healthyListener, _ -> {}, rethrowing());
        clock.tick();
        var failure = new RuntimeException("boom");
        var failures = new ArrayList<RuntimeException>();

        listeners.removeAll(listener -> {
            if (listener == failingListener) {
                throw failure;
            }
            listener.add("last");
        }, (listener, e) -> {
            assertThat(listener).isSameAs(failingListener);
            failures.add(e);
        });

        assertThat(failures).containsExactly(failure);
        assertThat(healthyListener).containsExactly("last");
        assertThat(listeners.size()).isZero();
    }

    @Test
    void aListenerMayCloseItsOwnSubscriptionOnItsLastNotification() {
        var runnableListeners = new SerialisedListeners<Runnable>();
        var ownSubscription = new MutableReference<Closeable>();
        var otherListenerNotifications = new ArrayList<String>();
        ownSubscription.set(runnableListeners.subscribe(executor, () -> ownSubscription.get().close(), _ -> {}, rethrowing()));
        runnableListeners.subscribe(executor, () -> otherListenerNotifications.add("last"), _ -> {}, rethrowing());
        clock.tick();

        runnableListeners.removeAll(Runnable::run, rethrowing());
        clock.tick();

        assertThat(otherListenerNotifications).containsExactly("last");
        assertThat(runnableListeners.size()).isZero();
    }

    @Test
    void aSubscriptionAddedOnALastNotificationIsKeptAndNotified() {
        var runnableListeners = new SerialisedListeners<Runnable>();
        var newListenerNotifications = new ArrayList<String>();
        Runnable newListener = () -> newListenerNotifications.add("change");
        runnableListeners.subscribe(executor, () -> runnableListeners.subscribe(executor, newListener, _ -> {}, rethrowing()), _ -> {}, rethrowing());
        clock.tick();

        runnableListeners.removeAll(Runnable::run, rethrowing());
        clock.tick();
        runnableListeners.publish(Runnable::run, rethrowing());

        assertThat(runnableListeners.size()).isOne();
        assertThat(newListenerNotifications).containsExactly("change");
    }

    @Test
    void aListenerMayCloseItsOwnSubscriptionWhileBeingNotified() {
        var runnableListeners = new SerialisedListeners<Runnable>();
        var ownSubscription = new MutableReference<Closeable>();
        var notifications = new ArrayList<String>();
        ownSubscription.set(runnableListeners.subscribe(executor, () -> {
            notifications.add("change");
            ownSubscription.get().close();
        }, _ -> {}, rethrowing()));
        clock.tick();

        runnableListeners.publish(Runnable::run, rethrowing());
        runnableListeners.publish(Runnable::run, rethrowing());
        clock.tick();

        assertThat(notifications).containsExactly("change");
        assertThat(runnableListeners.size()).isZero();
    }

    /// On a direct executor the closing removes the subscription while the notification is still being delivered.
    @Test
    void aListenerMayCloseALaterSubscriptionWhileBeingNotified() {
        var runnableListeners = new SerialisedListeners<Runnable>();
        var laterSubscription = new MutableReference<Closeable>();
        var notifications = new ArrayList<String>();
        runnableListeners.subscribe(directExecutor(), () -> {
            notifications.add("closing");
            laterSubscription.get().close();
        }, _ -> {}, rethrowing());
        laterSubscription.set(runnableListeners.subscribe(directExecutor(), () -> notifications.add("closed"), _ -> {}, rethrowing()));

        runnableListeners.publish(Runnable::run, rethrowing());
        runnableListeners.publish(Runnable::run, rethrowing());

        assertThat(notifications).containsExactly("closing", "closing");
        assertThat(runnableListeners.size()).isOne();
    }

    /// On a direct executor the subscription is added while the notification is still being delivered.
    @Test
    void aSubscriptionAddedWhileAListenerIsNotifiedReceivesOnlyTheLaterNotifications() {
        var newListenerNotifications = new ArrayList<String>();
        var subscribed = new MutableReference<>(false);
        listeners.subscribe(directExecutor(), new ArrayList<>(), _ -> {}, rethrowing());

        listeners.publish(_ -> {
            if (!subscribed.get()) {
                subscribed.set(true);
                listeners.subscribe(directExecutor(), newListenerNotifications, listener -> listener.add("image"), rethrowing());
            }
        }, rethrowing());
        listeners.publish(listener -> listener.add("later"), rethrowing());

        assertThat(newListenerNotifications).containsExactly("image", "later");
        assertThat(listeners.size()).isEqualTo(2);
    }

    /// On a direct executor the closing removes the subscription while both notifications are being delivered.
    @Test
    void aListenerMayPublishWhileBeingNotifiedAfterClosingASubscription() {
        var closedListener = new ArrayList<String>();
        var publishingListener = new ArrayList<String>();
        var otherListener = new ArrayList<String>();
        Closeable closedSubscription = listeners.subscribe(directExecutor(), closedListener, _ -> {}, rethrowing());
        listeners.subscribe(directExecutor(), publishingListener, _ -> {}, rethrowing());
        listeners.subscribe(directExecutor(), otherListener, _ -> {}, rethrowing());
        var sizesDuringPublish = new ArrayList<Integer>();

        listeners.publish(listener -> {
            listener.add("outer");
            if (listener == publishingListener) {
                closedSubscription.close();
                sizesDuringPublish.add(listeners.size());
                listeners.publish(innerListener -> innerListener.add("inner"), rethrowing());
            }
        }, rethrowing());

        assertThat(closedListener).containsExactly("outer");
        assertThat(publishingListener).containsExactly("outer", "inner");
        assertThat(otherListener).containsExactly("inner", "outer");
        assertThat(sizesDuringPublish).containsExactly(2);
        assertThat(listeners.size()).isEqualTo(2);
    }

    @Test
    void aListenerMayRemoveAllWhileBeingNotifiedAndNoListenerReceivesTheNotificationAfterIt() {
        var removingListener = new ArrayList<String>();
        var otherListener = new ArrayList<String>();
        listeners.subscribe(directExecutor(), removingListener, _ -> {}, rethrowing());
        listeners.subscribe(directExecutor(), otherListener, _ -> {}, rethrowing());

        listeners.publish(listener -> {
            listener.add("change");
            if (listener == removingListener) {
                listeners.removeAll(removedListener -> removedListener.add("last"), rethrowing());
            }
        }, rethrowing());

        assertThat(removingListener).containsExactly("change", "last");
        assertThat(otherListener).containsExactly("last");
        assertThat(listeners.size()).isZero();
    }

    private static <L> BiConsumer<L, RuntimeException> rethrowing() {
        return (_, e) -> {
            throw e;
        };
    }
}
