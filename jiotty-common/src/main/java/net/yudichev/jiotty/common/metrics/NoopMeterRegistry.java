package net.yudichev.jiotty.common.metrics;

import io.micrometer.core.instrument.composite.CompositeMeterRegistry;

/// A no-op metrics registry: an empty [CompositeMeterRegistry] with no backing registries, so meters created against it delegate nowhere — measurements are
/// dropped rather than recorded or retained. Use it where Micrometer instrumentation needs a registry to bind to but no metrics are collected: the no-op
/// production wiring ([NoopMetricsModule]) and tests that don't assert on metrics.
public final class NoopMeterRegistry extends CompositeMeterRegistry {
}
