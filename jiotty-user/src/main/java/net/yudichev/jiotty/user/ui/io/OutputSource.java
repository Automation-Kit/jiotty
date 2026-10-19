package net.yudichev.jiotty.user.ui.io;

import java.util.List;

/// A source of the response bodies a [ContainerOutput] writes, in send order.
///
/// @implSpec an implementation must be safe to call from any thread.
public interface OutputSource {
    /// Removes and returns the bodies ready now, in send order; empty when none is ready.
    List<? extends ResponseBody> takeReady();

    /// Returns whether a body is ready, without removing it.
    boolean hasReady();

    /// Returns whether this source has produced its last body.
    boolean isFinished();
}
