package net.yudichev.jiotty.user.persistence;

import net.yudichev.jiotty.user.persistence.RecordingUserRecordListener.RecordEvent;
import net.yudichev.jiotty.user.persistence.UserPersistence.UserRecordListener;

import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.CompletableFuture.completedFuture;

/// A [UserRecordListener] that records each delivery.
class RecordingUserRecordListener extends BaseRecordingListener<UserRecord, RecordEvent> implements UserRecordListener {
    private final CompletableFuture<Throwable> failure = new CompletableFuture<>();

    @Override
    public void onSubscriptionFailed(Throwable failure) {
        super.onSubscriptionFailed(failure);
        this.failure.complete(failure);
    }

    /// @return a future that completes with the failure that ends the subscription
    public CompletableFuture<Throwable> failure() {
        return failure;
    }

    @Override
    public void onChanged(UserRecord record) {
        recordChange(new Changed(record));
    }

    @Override
    public void onDaysPurged(LocalDate day) {
        recordChange(new DaysPurged(day));
    }

    @Override
    public CompletableFuture<?> onRemoved(String userId) {
        recordChange(new Removed(userId));
        return completedFuture(null);
    }

    public sealed interface RecordEvent permits Changed, DaysPurged, Removed {}

    public record Changed(UserRecord record) implements RecordEvent {}

    public record DaysPurged(LocalDate day) implements RecordEvent {}

    public record Removed(String userId) implements RecordEvent {}
}
