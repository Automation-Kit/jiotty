package net.yudichev.jiotty.user.persistence;

import net.yudichev.jiotty.user.persistence.UserPersistence.UserStateListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.user.persistence.UserPersistenceTestTimeouts.DELIVERY_TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;

/// Records what a [UserStateListener] receives. The image is delivered by a task of its own, so it is awaited; each change is delivered before the write that
/// caused it completes.
class RecordingUserStateListener implements UserStateListener {
    private final CompletableFuture<List<UserProfileWithDeletion>> image = new CompletableFuture<>();
    /// Synchronised: the persistence thread records into it, and the test thread reads it.
    private final List<StateEvent> changes = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void onImage(List<UserProfileWithDeletion> users) {
        image.complete(users);
    }

    @Override
    public void onChanged(UserProfileWithDeletion user) {
        changes.add(new Changed(user));
    }

    @Override
    public CompletionStage<?> onRemoved(String userId) {
        changes.add(new Removed(userId));
        return completedFuture(null);
    }

    @Override
    public void onSubscriptionFailed(Throwable failure) {
        image.completeExceptionally(failure);
    }

    /// Completes with the image, or fails with the subscription's failure.
    public CompletableFuture<List<UserProfileWithDeletion>> image() {
        return image;
    }

    public boolean imageArrived() {
        return image.isDone();
    }

    public void awaitImage() {
        assertThat(image).succeedsWithin(DELIVERY_TIMEOUT);
    }

    /// The changes delivered after the image, which is awaited first.
    public List<StateEvent> changes() {
        awaitImage();
        synchronized (changes) {
            return List.copyOf(changes);
        }
    }

    public sealed interface StateEvent permits Changed, Removed {}

    public record Changed(UserProfileWithDeletion user) implements StateEvent {}

    public record Removed(String userId) implements StateEvent {}
}
