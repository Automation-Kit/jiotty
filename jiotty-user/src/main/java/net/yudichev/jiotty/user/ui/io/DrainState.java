package net.yudichev.jiotty.user.ui.io;

import static com.google.common.base.Preconditions.checkNotNull;

/// The ownership state of a [ContainerOutput]'s stream, moved by one compare-and-set per transition so that at most one thread writes at a time.
sealed interface DrainState {
    enum Phase implements DrainState {
        /// Nothing being written.
        IDLE,
        /// A drain has been handed to a container thread and has not started. The container may drop that task when the exchange ends first, so nothing
        /// waits on it: whichever of the task and an end moves the state on first wins.
        QUEUED,
        /// One container thread is writing.
        DRAINING,
        /// A write is in progress, and the container calls back once the next one may start.
        PARKED,
        /// The exchange has ended.
        CLOSED
    }

    /// An end requested while a thread is draining; the draining thread ends the exchange at its next state check.
    record Closing(EndReason reason) implements DrainState {
        public Closing {
            checkNotNull(reason, "reason");
        }
    }
}
