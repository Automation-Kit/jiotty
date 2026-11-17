package net.yudichev.jiotty.common.lang;

import net.yudichev.jiotty.common.testutil.LogCapture;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class SerialisedListenersTest {
    private static final String REJECTION_MESSAGE = "queue full";

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

        listeners.notify(_ -> {throw new RejectedExecutionException(REJECTION_MESSAGE);}, (_, e) -> failures.add(e));
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
    void removingAllDeliversALastNotificationToEachOpenListenerAndNotifiesNoneAfterwards() {
        var openListener = new ArrayList<String>();
        var closedListener = new ArrayList<String>();
        listeners.add(listeners.newSubscription(openListener), _ -> {}, rethrowing());
        SerialisedListeners<List<String>>.Subscription closedSubscription = listeners.newSubscription(closedListener);
        listeners.add(closedSubscription, _ -> {}, rethrowing());
        closedSubscription.close();

        listeners.removeAll(listener -> listener.add("last"));
        listeners.notify(listener -> listener.add("change"), rethrowing());

        assertThat(openListener).containsExactly("last");
        assertThat(closedListener).isEmpty();
        assertThat(listeners.size()).isZero();
    }

    @ParameterizedTest
    @MethodSource
    void aListenerThatThrowsOnItsLastNotificationLeavesTheOthersToldAndRemoved(RuntimeException failure) {
        var failingListener = new ArrayList<String>();
        var healthyListener = new ArrayList<String>();
        listeners.add(listeners.newSubscription(failingListener), _ -> {}, rethrowing());
        listeners.add(listeners.newSubscription(healthyListener), _ -> {}, rethrowing());

        listeners.removeAll(listener -> {
            if (listener == failingListener) {
                throw failure;
            }
            listener.add("last");
        });

        assertThat(healthyListener).containsExactly("last");
        assertThat(listeners.size()).isZero();
    }

    static Stream<Arguments> aListenerThatThrowsOnItsLastNotificationLeavesTheOthersToldAndRemoved() {
        return Stream.of(arguments(new RuntimeException("boom")),
                         // A listener's executor throws this exception when its queue is full.
                         arguments(new RejectedExecutionException(REJECTION_MESSAGE)));
    }

    /// A last notification that the listener's executor rejects is logged at DEBUG.
    @Test
    void aRejectedLastNotificationIsNotLoggedAtInfo() {
        listeners.add(listeners.newSubscription(new ArrayList<>()), _ -> {}, rethrowing());

        try (LogCapture log = LogCapture.capture(SerialisedListeners.class, Level.INFO)) {
            listeners.removeAll(_ -> {throw new RejectedExecutionException(REJECTION_MESSAGE);});

            assertThat(log.lines()).filteredOn(line -> line.text().anyMatch(text -> text.contains(REJECTION_MESSAGE))).isEmpty();
        }
    }

    @Test
    void aListenerThatThrowsOnItsLastNotificationIsLoggedAtInfo() {
        listeners.add(listeners.newSubscription(new ArrayList<>()), _ -> {}, rethrowing());
        var failure = new RuntimeException("boom");

        try (LogCapture log = LogCapture.capture(SerialisedListeners.class, Level.INFO)) {
            listeners.removeAll(_ -> {throw failure;});

            assertThat(log.lines()).singleElement()
                                   .satisfies(line -> {
                                       assertThat(line.level()).isEqualTo(Level.INFO);
                                       assertThat(line.thrown()).containsExactly(failure.toString());
                                   });
        }
    }

    @Test
    void aListenerMayCloseItsOwnSubscriptionOnItsLastNotification() {
        var synchronousListeners = new SerialisedListeners<Runnable>(Runnable::run);
        var ownSubscription = new MutableReference<Closeable>();
        var otherListenerNotifications = new ArrayList<String>();
        SerialisedListeners<Runnable>.Subscription subscription = synchronousListeners.newSubscription(() -> ownSubscription.get().close());
        ownSubscription.set(subscription);
        synchronousListeners.add(subscription, _ -> {}, rethrowing());
        synchronousListeners.add(synchronousListeners.newSubscription(() -> otherListenerNotifications.add("last")), _ -> {}, rethrowing());

        synchronousListeners.removeAll(Runnable::run);

        assertThat(otherListenerNotifications).containsExactly("last");
        assertThat(synchronousListeners.size()).isZero();
    }

    @Test
    void aSubscriptionAddedOnALastNotificationIsKeptAndNotified() {
        var synchronousListeners = new SerialisedListeners<Runnable>(Runnable::run);
        var newListenerNotifications = new ArrayList<String>();
        SerialisedListeners<Runnable>.Subscription newSubscription = synchronousListeners.newSubscription(() -> newListenerNotifications.add("change"));
        synchronousListeners.add(synchronousListeners.newSubscription(() -> synchronousListeners.add(newSubscription, _ -> {}, rethrowing())),
                                 _ -> {},
                                 rethrowing());

        synchronousListeners.removeAll(Runnable::run);
        synchronousListeners.notify(Runnable::run, rethrowing());

        assertThat(synchronousListeners.size()).isOne();
        assertThat(newListenerNotifications).containsExactly("change");
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
