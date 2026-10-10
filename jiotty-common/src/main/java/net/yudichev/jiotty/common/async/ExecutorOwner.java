package net.yudichev.jiotty.common.async;

/// What an executor reports a full queue to. An executor has the owner bound to [#CURRENT] when it is constructed, or none.
public interface ExecutorOwner {
    /// The owner of an executor constructed while it is bound.
    ScopedValue<ExecutorOwner> CURRENT = ScopedValue.newInstance();

    /// Called once by each owned executor as it is constructed.
    ///
    /// @param backlogDiscarder makes the immediate tasks that executor has queued so far be skipped; runs on the panicking thread, so it must neither block
    ///                         nor take a lock
    /// @implSpec the discarder must not be run concurrently with itself
    void addBacklogDiscarder(Runnable backlogDiscarder);

    /// Called by an owned executor on the thread whose task it rejected for a full queue, before throwing `rejection` to that thread.
    ///
    /// @implSpec must not block, and must [mark][QueueFullException#markOwnerPanicked()] `rejection` once this owner has panicked over it, now or
    ///           earlier, before anything that may throw
    void onQueueFull(String executorName, QueueFullException rejection);
}
