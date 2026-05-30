package net.yudichev.jiotty.common.metrics;

import com.google.inject.AbstractModule;
import io.micrometer.core.instrument.MeterRegistry;

/// Binds a no-op [MeterRegistry] ([NoopMeterRegistry]) for deployments that instrument with Micrometer but export metrics nowhere: measurements are discarded
/// rather than recorded or retained, and no scrape endpoint is opened. Install this in place of [MetricsModule] where a metrics backend is neither available
/// nor wanted.
public final class NoopMetricsModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(MeterRegistry.class).toInstance(new NoopMeterRegistry());
    }
}
