package net.yudichev.jiotty.common.metrics;

import com.google.inject.BindingAnnotation;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/// Constructs the singleton [PrometheusMeterRegistry] for [MetricsModule] and applies two registry-wide [MeterFilter]s:
///   - **Common tags** — every meter (including the JVM binders that attach later in the lifecycle) inherits the tags passed via [CommonTags].
///   - **Percentile histograms** — every [Meter.Type#TIMER] emits `_bucket` series alongside `_count`/`_sum`, so server-side `histogram_quantile() queries
///     work without each call site having to opt in.
public final class PrometheusMeterRegistryProvider implements Provider<PrometheusMeterRegistry> {
    private final Tags commonTags;

    @Inject
    public PrometheusMeterRegistryProvider(@CommonTags Tags commonTags) {
        this.commonTags = checkNotNull(commonTags);
    }

    @Override
    public PrometheusMeterRegistry get() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        if (commonTags.iterator().hasNext()) {
            registry.config().meterFilter(MeterFilter.commonTags(commonTags));
        }
        registry.config().meterFilter(new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (id.getType() == Meter.Type.TIMER) {
                    return DistributionStatisticConfig.builder()
                                                      .percentilesHistogram(true)
                                                      .build()
                                                      .merge(config);
                }
                return config;
            }
        });
        return registry;
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface CommonTags {
    }
}
