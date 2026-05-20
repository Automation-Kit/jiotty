package net.yudichev.jiotty.analyticscache;

import java.time.Duration;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/// Slot grain for one [TimeSeriesCache] instance. Wraps a positive [Duration] with whole-second precision; sub-second components are rejected at construction.
///
/// Prefer the named factories ([#daily], [#halfHourly], [#forStep]) at call sites — they make the common grains self-documenting.
///
/// @param step positive duration; must have no nanosecond component
public record Resolution(Duration step) {
    public Resolution {
        checkNotNull(step, "step");
        checkArgument(!step.isZero() && !step.isNegative(), "step must be positive: %s", step);
        checkArgument(step.getNano() == 0, "step must have whole-second precision (no nanosecond component): %s", step);
    }

    /// One-day grain (24 hours).
    public static Resolution daily() {
        return new Resolution(Duration.ofDays(1));
    }

    /// 30-minute grain.
    public static Resolution halfHourly() {
        return new Resolution(Duration.ofMinutes(30));
    }

    /// Creates a [Resolution] from any positive whole-second [Duration].
    public static Resolution forStep(Duration step) {
        return new Resolution(step);
    }
}
