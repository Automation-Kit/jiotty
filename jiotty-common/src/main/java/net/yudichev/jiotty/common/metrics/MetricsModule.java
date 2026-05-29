package net.yudichev.jiotty.common.metrics;

import com.google.inject.Module;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import jakarta.inject.Singleton;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.lang.TypedBuilder;
import net.yudichev.jiotty.common.rest.RestServerModule;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;

/// Wires a single-process Prometheus-based metrics stack: exposes [MeterRegistry] and its concrete [PrometheusMeterRegistry] for application code to use as an
/// instrumentation sink, and hosts an internal admin Javalin REST server that serves the registry scrape on `/metrics`.
///
/// The admin REST server (provided via an internally-installed [RestServerModule]) stays internal to this module. Reachability of the scrape port is the
/// caller's responsibility — typically a sibling Prometheus container on the docker network. The port defaults to `9101` and can be overridden via
/// [Builder#withListenPort].
///
/// JVM-binder metrics (gc, memory, threads, classloader, processor, uptime) are registered automatically as part of this module's lifecycle.
///
/// Common tags declared via [Builder#withCommonTag] and the timer percentile-histogram filter are applied by [PrometheusMeterRegistryProvider] when the
/// registry is provisioned.
public final class MetricsModule extends BaseLifecycleComponentModule {
    private final BindingSpec<Integer> listenPortSpec;
    private final Tags commonTags;

    private MetricsModule(BindingSpec<Integer> listenPortSpec, Tags commonTags) {
        this.listenPortSpec = checkNotNull(listenPortSpec);
        this.commonTags = checkNotNull(commonTags);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        installLifecycleComponentModule(RestServerModule.builder()
                                                        .withAnnotation(forAnnotation(MetricsHttpHandler.Dependency.class))
                                                        .withListenPort(listenPortSpec)
                                                        .build());
        registerLifecycleComponent(JvmMetricsBinder.class);
        registerLifecycleComponent(MetricsHttpHandler.class);

        bind(Tags.class).annotatedWith(PrometheusMeterRegistryProvider.CommonTags.class).toInstance(commonTags);
        bind(PrometheusMeterRegistry.class).toProvider(PrometheusMeterRegistryProvider.class).in(Singleton.class);
        bind(MeterRegistry.class).to(PrometheusMeterRegistry.class);
        expose(MeterRegistry.class);
        expose(PrometheusMeterRegistry.class);
    }

    public static final class Builder implements TypedBuilder<Module> {
        private BindingSpec<Integer> listenPortSpec = literally(9101);
        private Tags commonTags = Tags.empty();

        public Builder withListenPort(BindingSpec<Integer> listenPortSpec) {
            this.listenPortSpec = checkNotNull(listenPortSpec);
            return this;
        }

        public Builder withCommonTag(String key, String value) {
            commonTags = commonTags.and(key, value);
            return this;
        }

        @Override
        public Module build() {
            return new MetricsModule(listenPortSpec, commonTags);
        }
    }
}
