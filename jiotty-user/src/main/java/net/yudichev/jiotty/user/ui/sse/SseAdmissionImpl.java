package net.yudichev.jiotty.user.ui.sse;

import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.metrics.GuardMetrics;

import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.base.Preconditions.checkArgument;
import static net.yudichev.jiotty.common.lang.AtomicIntegers.tryIncrementBelow;
import static net.yudichev.jiotty.common.metrics.GuardMetrics.OUTCOME_AT_CAPACITY;
import static net.yudichev.jiotty.common.metrics.GuardMetrics.OUTCOME_USER_AT_CAPACITY;
import static net.yudichev.jiotty.common.metrics.GuardMetrics.REJECTED_COUNTER;
import static net.yudichev.jiotty.common.metrics.GuardMetrics.TAG_GUARD;
import static net.yudichev.jiotty.common.metrics.GuardMetrics.TAG_OUTCOME;

/// A budget of a fixed number of streams, metered as {@value #ACTIVE_CLIENTS_GAUGE} against {@value #ACTIVE_CLIENTS_LIMIT_GAUGE}, with every refusal counted
/// on [GuardMetrics#REJECTED_COUNTER] under the guard {@value #GUARD}.
public final class SseAdmissionImpl implements SseAdmission {
    /// Must stay below the reverse proxy's connection limit to this server, so a stream past it is refused here with an immediate 503.
    @VisibleForTesting
    static final int MAX_STREAMS = 300;
    @VisibleForTesting
    static final String ACTIVE_CLIENTS_GAUGE = "sse_active_clients";
    @VisibleForTesting
    static final String ACTIVE_CLIENTS_LIMIT_GAUGE = "sse_active_clients_limit";
    @VisibleForTesting
    static final String DROPPED_COUNTER = "sse_client_dropped_total";
    @VisibleForTesting
    static final String GUARD = "sse";

    private final int maxStreams;
    /// Taken on the servlet threads that open streams, and given back on whichever thread ends one: a container thread, the channel executor, or a feature
    /// thread whose send dropped a slow reader.
    private final AtomicInteger activeStreams = new AtomicInteger();
    private final Counter atCapacityCounter;
    private final Counter channelFullCounter;
    private final Counter droppedCounter;

    @Inject
    public SseAdmissionImpl(MeterRegistry meterRegistry) {
        this(MAX_STREAMS, meterRegistry);
    }

    @VisibleForTesting
    SseAdmissionImpl(int maxStreams, MeterRegistry meterRegistry) {
        checkArgument(maxStreams > 0, "maxStreams must be positive: %s", maxStreams);
        this.maxStreams = maxStreams;
        atCapacityCounter = meterRegistry.counter(REJECTED_COUNTER, TAG_GUARD, GUARD, TAG_OUTCOME, OUTCOME_AT_CAPACITY);
        channelFullCounter = meterRegistry.counter(REJECTED_COUNTER, TAG_GUARD, GUARD, TAG_OUTCOME, OUTCOME_USER_AT_CAPACITY);
        droppedCounter = meterRegistry.counter(DROPPED_COUNTER);
        // Both take the supplier form: the value-and-function form holds its subject weakly, and a boxed limit that nothing else retains is collectable —
        // the gauge then reports NaN and the ratio alert stops evaluating.
        Gauge.builder(ACTIVE_CLIENTS_GAUGE, activeStreams::get).register(meterRegistry);
        Gauge.builder(ACTIVE_CLIENTS_LIMIT_GAUGE, () -> maxStreams).register(meterRegistry);
    }

    @Override
    public boolean tryAdmit() {
        if (tryIncrementBelow(activeStreams, maxStreams)) {
            return true;
        }
        atCapacityCounter.increment();
        return false;
    }

    @Override
    public void release() {
        activeStreams.decrementAndGet();
    }

    @Override
    public void recordChannelFull() {
        channelFullCounter.increment();
    }

    @Override
    public void recordSlowReaderDropped() {
        droppedCounter.increment();
    }
}
