package net.yudichev.jiotty.timeseriescache;

import com.google.common.collect.ImmutableMap;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

final class TimeSeriesCacheUtil {
    /// Walks the slot grid `[from, to]` at `step` and emits, in chronological order, only the slots that carry a present value. Each slot's state is taken from
    /// `hits` first (the cached rows) then `computedValues` (the freshly-resolved rows); a slot whose [Optional] is empty is a tombstone (definitively empty)
    /// and a slot absent from both maps was never resolved — both are excluded from the result.
    public static <T> ImmutableMap<Instant, T> buildOrderedMap(Instant from,
                                                               Instant to,
                                                               Duration step,
                                                               Map<Instant, Optional<T>> hits,
                                                               Map<Instant, Optional<T>> computedValues) {
        var out = ImmutableMap.<Instant, T>builderWithExpectedSize(hits.size() + computedValues.size());
        for (Instant slot = from; !slot.isAfter(to); slot = slot.plus(step)) {
            // Neither map ever stores a null value (every entry is an Optional), so a null from get() unambiguously means "absent" — one lookup per map,
            //  no containsKey probe. hits wins over computedValues for a slot present in both; defaulting the fallback to empty() keeps value non-null so it
            //  is null-checked once.
            Optional<T> hit = hits.get(slot);
            Optional<T> value = hit != null ? hit : computedValues.getOrDefault(slot, Optional.empty());
            if (value.isPresent()) {
                out.put(slot, value.get());
            }
        }
        return out.build();
    }
}
