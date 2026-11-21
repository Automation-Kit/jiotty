package net.yudichev.jiotty.common.observer;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// The listeners of one notifier, in the order of adding. Every method must be called on one thread. A listener may add and remove listeners while it is
/// notified.
///
/// A notification runs between [#startNotification()] and [#endNotification()], and loops by index over the list that [#startNotification()] returns, up
/// to the size that list has then. An element removed while a notification runs is `null` in that list until the outermost notification ends, so that
/// the index of every other element stays the same for each notification in progress.
///
/// @param <E> the type of the listeners
final class ListenerList<E> {
    private List<@Nullable E> elements = new ArrayList<>();
    private int notificationDepth;
    /// Whether the list holds a `null` to remove when the outermost notification ends.
    private boolean holdsRemovedElements;

    public void add(E element) {
        elements.add(element);
    }

    /// Removes `element`, if this list holds it.
    public void remove(E element) {
        int index = elements.indexOf(element);
        if (index >= 0) {
            removeAt(elements, index);
        }
    }

    /// Removes the element at `index` of `notified`, if `notified` still [holds the listeners][#holdsListeners].
    ///
    /// @param notified the list that [#startNotification()] returned
    public void removeAt(List<@Nullable E> notified, int index) {
        if (holdsListeners(notified)) {
            if (notificationDepth == 0) {
                elements.remove(index);
            } else {
                elements.set(index, null);
                holdsRemovedElements = true;
            }
        }
    }

    /// Starts a notification, which [#endNotification()] must end.
    ///
    /// @return the list to loop over by index, whose `null` elements are removed listeners to skip
    public List<@Nullable E> startNotification() {
        notificationDepth++;
        return elements;
    }

    /// @param notified the list that [#startNotification()] returned
    /// @return `false` once [#removeAll] has replaced `notified`
    public boolean holdsListeners(List<@Nullable E> notified) {
        return notified == elements;
    }

    public void endNotification() {
        if (--notificationDepth == 0 && holdsRemovedElements) {
            holdsRemovedElements = false;
            elements.removeIf(Objects::isNull);
        }
    }

    public boolean isNotifying() {
        return notificationDepth > 0;
    }

    public int size() {
        int count = 0;
        for (E element : elements) {
            if (element != null) {
                count++;
            }
        }
        return count;
    }

    /// Removes every listener. A notification in progress goes on over the list that it started with.
    ///
    /// @return the removed listeners, whose `null` elements are listeners removed earlier
    public List<@Nullable E> removeAll() {
        List<@Nullable E> removed = elements;
        elements = new ArrayList<>();
        return removed;
    }
}
