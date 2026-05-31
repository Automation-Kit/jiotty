package net.yudichev.jiotty.timeseriescache;

import com.google.common.collect.ImmutableMap;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

final class TimeSeriesCacheUtil {
    public static <T> ImmutableMap<Instant, T> buildOrderedMap(Instant from,
                                                               Instant to,
                                                               Duration step,
                                                               Map<Instant, T> hits,
                                                               Map<Instant, T> computedValues) {
        var out = ImmutableMap.<Instant, T>builderWithExpectedSize(hits.size() + computedValues.size());
        for (Instant slot = from; !slot.isAfter(to); slot = slot.plus(step)) {
            T hitValue = hits.get(slot);
            T value = hitValue != null ? hitValue : computedValues.get(slot);
            if (value != null) {
                out.put(slot, value);
            }
        }
        return out.build();
    }
}
