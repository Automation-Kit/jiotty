package net.yudichev.jiotty.user.persistence;

import net.yudichev.jiotty.user.persistence.RecordingUserStateListener.StateEvent;
import net.yudichev.jiotty.user.persistence.UserPersistence.UserStateListener;

import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.CompletableFuture.completedFuture;

/// A [UserStateListener] that records each delivery.
class RecordingUserStateListener extends BaseRecordingListener<UserProfileWithDeletion, StateEvent> implements UserStateListener {
    public boolean imageArrived() {
        return image().isDone();
    }

    @Override
    public void onChanged(UserProfileWithDeletion user) {
        recordChange(new Changed(user));
    }

    @Override
    public CompletableFuture<?> onRemoved(String userId) {
        recordChange(new Removed(userId));
        return completedFuture(null);
    }

    public sealed interface StateEvent permits Changed, Removed {}

    public record Changed(UserProfileWithDeletion user) implements StateEvent {}

    public record Removed(String userId) implements StateEvent {}
}
