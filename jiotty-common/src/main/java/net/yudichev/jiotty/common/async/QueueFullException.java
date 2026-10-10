package net.yudichev.jiotty.common.async;

import java.util.concurrent.RejectedExecutionException;

import static com.google.common.base.Preconditions.checkNotNull;

/// Thrown by an executor whose queue of immediate tasks is full.
public final class QueueFullException extends RejectedExecutionException {
    /// Written by the rejecting executor's owner before the executor throws this exception, and read by whoever catches it.
    @SuppressWarnings("NonFinalFieldOfException") // the owner's verdict is known only after the owner has been handed this exception as the panic's cause
    private boolean ownerPanicked;

    QueueFullException(String message) {
        super(checkNotNull(message));
    }

    /// Whether the rejecting executor's [ExecutorOwner] has panicked over this rejection, now or earlier.
    public boolean ownerPanicked() {
        return ownerPanicked;
    }

    /// Must be called only by the rejecting executor's [ExecutorOwner], once it has panicked over this rejection and before the executor throws it.
    public void markOwnerPanicked() {
        ownerPanicked = true;
    }
}
