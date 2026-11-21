package net.yudichev.jiotty.common.observer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ListenerListTest {
    private final ListenerList<String> list = new ListenerList<>();

    @Test
    void anElementRemovedOutsideANotificationLeavesTheListAtOnce() {
        list.add("a");
        list.add("b");

        list.remove("a");

        assertThat(list.startNotification()).containsExactly("b");
        list.endNotification();
        assertThat(list.size()).isOne();
    }

    @Test
    void anElementRemovedDuringANotificationIsNullUntilTheOutermostNotificationEnds() {
        list.add("a");
        list.add("b");
        var notified = list.startNotification();
        list.startNotification();

        list.removeAt(notified, 0);
        list.endNotification();

        assertThat(notified).containsExactly(null, "b");
        assertThat(list.size()).isOne();
        assertThat(list.isNotifying()).isTrue();

        list.endNotification();

        assertThat(list.isNotifying()).isFalse();
        assertThat(list.startNotification()).containsExactly("b");
    }

    @Test
    void removingAnElementThatTheListDoesNotHoldChangesNothing() {
        list.add("a");

        list.remove("b");

        assertThat(list.size()).isOne();
    }

    @Test
    void removeAllReturnsTheListenersAndLeavesANotificationInProgressOnItsOwnList() {
        list.add("a");
        list.add("b");
        var notified = list.startNotification();
        list.removeAt(notified, 0);

        var removed = list.removeAll();
        list.add("c");
        list.removeAt(notified, 1);
        list.endNotification();

        assertThat(removed).containsExactly(null, "b");
        assertThat(list.startNotification()).containsExactly("c");
    }

    @Test
    void anElementAddedDuringANotificationIsBeyondTheSizeTheNotificationStartedWith() {
        list.add("a");
        var notified = list.startNotification();
        int startSize = notified.size();

        list.add("b");
        list.endNotification();

        assertThat(startSize).isOne();
        assertThat(notified).containsExactly("a", "b");
    }
}
