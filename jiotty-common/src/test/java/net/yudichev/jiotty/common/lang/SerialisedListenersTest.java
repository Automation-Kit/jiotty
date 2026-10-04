package net.yudichev.jiotty.common.lang;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

class SerialisedListenersTest {
    private final List<Runnable> ownerTasks = new ArrayList<>();
    private final SerialisedListeners<List<String>> listeners = new SerialisedListeners<>(ownerTasks::add);

    @Test
    void deliversTheImageThenEveryNotification() {
        var receivedNotifications = new ArrayList<String>();

        listeners.add(listeners.newSubscription(receivedNotifications), listener -> listener.add("image"), rethrowing());
        listeners.notify(listener -> listener.add("first"), rethrowing());
        listeners.notify(listener -> listener.add("second"), rethrowing());

        assertThat(receivedNotifications).containsExactly("image", "first", "second");
    }

    @Test
    void aSubscriptionClosedBeforeItIsAddedReceivesNothing() {
        var receivedNotifications = new ArrayList<String>();
        SerialisedListeners<List<String>>.Subscription subscription = listeners.newSubscription(receivedNotifications);

        subscription.close();
        listeners.add(subscription, listener -> listener.add("image"), rethrowing());
        listeners.notify(listener -> listener.add("change"), rethrowing());

        assertThat(receivedNotifications).isEmpty();
    }

    @Test
    void aListenerThatThrowsOnItsImageIsReportedAndLeftOut() {
        var receivedNotifications = new ArrayList<String>();
        var failures = new ArrayList<String>();

        listeners.add(listeners.newSubscription(receivedNotifications),
                      _ -> {throw new RuntimeException("image boom");},
                      (_, e) -> failures.add(e.getMessage()));
        listeners.notify(listener -> listener.add("change"), rethrowing());

        assertThat(failures).containsExactly("image boom");
        assertThat(receivedNotifications).isEmpty();
    }

    @Test
    void aListenerWhoseExecutorRejectsANotificationStaysSubscribedAndIsNotReported() {
        var receivedNotifications = new ArrayList<String>();
        var failures = new ArrayList<RuntimeException>();
        listeners.add(listeners.newSubscription(receivedNotifications), _ -> {}, rethrowing());

        listeners.notify(_ -> {throw new RejectedExecutionException("queue full");}, (_, e) -> failures.add(e));
        listeners.notify(listener -> listener.add("next"), rethrowing());

        assertThat(failures).isEmpty();
        assertThat(receivedNotifications).containsExactly("next");
    }

    /// A listener that throws on a notification has missed it, so it is dropped and notified no more.
    @Test
    void aThrowingListenerIsReportedDroppedAndLeavesTheOthersNotified() {
        var failingListener = new ArrayList<String>();
        var healthyListener = new ArrayList<String>();
        listeners.add(listeners.newSubscription(failingListener), _ -> {}, rethrowing());
        listeners.add(listeners.newSubscription(healthyListener), _ -> {}, rethrowing());
        var failures = new ArrayList<List<String>>();

        listeners.notify(listener -> {
            if (listener == failingListener) {
                throw new RuntimeException("boom");
            }
            listener.add("first");
        }, (listener, _) -> failures.add(listener));
        listeners.notify(listener -> listener.add("second"), rethrowing());

        assertThat(failures).containsExactly(failingListener);
        assertThat(failingListener).isEmpty();
        assertThat(healthyListener).containsExactly("first", "second");
        assertThat(listeners.size()).isOne();
    }

    /// Closing hands the removal to the removal executor; until that runs, the next notification skips and drops the closed subscription itself.
    @Test
    void aClosedSubscriptionIsNotifiedNoMore() {
        var receivedNotifications = new ArrayList<String>();
        SerialisedListeners<List<String>>.Subscription subscription = listeners.newSubscription(receivedNotifications);
        listeners.add(subscription, _ -> {}, rethrowing());

        subscription.close();
        listeners.notify(listener -> listener.add("change"), rethrowing());
        ownerTasks.forEach(Runnable::run);

        assertThat(receivedNotifications).isEmpty();
        assertThat(listeners.size()).isZero();
    }

    @Test
    void aListenerMayCloseItsOwnSubscriptionWhileBeingNotified() {
        var synchronousListeners = new SerialisedListeners<Runnable>(Runnable::run);
        var ownSubscription = new MutableReference<Closeable>();
        var notifications = new ArrayList<String>();
        SerialisedListeners<Runnable>.Subscription subscription = synchronousListeners.newSubscription(() -> {
            notifications.add("change");
            ownSubscription.get().close();
        });
        ownSubscription.set(subscription);
        synchronousListeners.add(subscription, _ -> {}, rethrowing());

        synchronousListeners.notify(Runnable::run, rethrowing());
        synchronousListeners.notify(Runnable::run, rethrowing());

        assertThat(notifications).containsExactly("change");
        assertThat(synchronousListeners.size()).isZero();
    }

    private static <L> BiConsumer<L, RuntimeException> rethrowing() {
        return (_, e) -> {
            throw e;
        };
    }
}
