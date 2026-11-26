package net.yudichev.jiotty.energy.octopus;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;

import static com.google.common.base.Preconditions.checkNotNull;

/// The start of the latest fetch, from which [#earliestNextStart] spaces the next one.
public final class FetchHistory {
    /// `null` until a fetch has started
    private @Nullable Instant latestStart;

    public void recordStart(Instant start) {
        latestStart = checkNotNull(start);
    }

    /// @return the later of `now` and the start of the latest fetch plus `spacing`; `now` when no fetch has started
    public Instant earliestNextStart(Instant now, Duration spacing) {
        if (latestStart == null) {
            return now;
        }
        Instant spacedStart = latestStart.plus(spacing);
        return spacedStart.isAfter(now) ? spacedStart : now;
    }
}
