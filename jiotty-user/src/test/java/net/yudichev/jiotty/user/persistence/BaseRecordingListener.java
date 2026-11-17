package net.yudichev.jiotty.user.persistence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static net.yudichev.jiotty.user.persistence.UserPersistenceTestTimeouts.DELIVERY_TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;

/// A base for listeners that record the deliveries of a [UserPersistence] subscription: the image, and each change after the image. The failure that ends
/// the subscription completes the image's future exceptionally. A subscription can deliver the image after the call that subscribed has returned, so
/// [#changes()] waits for the image. A subscription delivers each change before the future of the write that made the change completes.
///
/// @param <I> the type of the image's elements
/// @param <C> the type of a recorded change
// AbstractClassWithoutAbstractMethods: this class is a base for subclasses only. Each subclass implements a listener interface, including the interface
// methods that this class does not define.
@SuppressWarnings("AbstractClassWithoutAbstractMethods")
abstract class BaseRecordingListener<I, C> {
    private final CompletableFuture<List<I>> image = new CompletableFuture<>();
    /// A synchronised list, because the store adds each change on its own thread while the test reads the list on the test thread.
    private final List<C> changes = Collections.synchronizedList(new ArrayList<>());

    public void onImage(List<I> image) {
        this.image.complete(image);
    }

    public void onSubscriptionFailed(Throwable failure) {
        image.completeExceptionally(failure);
    }

    /// @return a future that completes with the image, or fails with the subscription's failure
    public CompletableFuture<List<I>> image() {
        return image;
    }

    public void awaitImage() {
        assertThat(image).succeedsWithin(DELIVERY_TIMEOUT);
    }

    /// Waits for the image, and fails the test if the image does not arrive within the delivery timeout.
    ///
    /// @return the changes delivered after the image
    public List<C> changes() {
        awaitImage();
        synchronized (changes) {
            return List.copyOf(changes);
        }
    }

    protected void recordChange(C change) {
        changes.add(change);
    }
}
