package net.yudichev.jiotty.common.metrics;

import com.google.inject.Module;
import com.google.inject.Provides;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
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
public final class MetricsModule extends BaseLifecycleComponentModule {
    private final BindingSpec<Integer> listenPortSpec;

    private MetricsModule(BindingSpec<Integer> listenPortSpec) {
        this.listenPortSpec = checkNotNull(listenPortSpec);
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

        bind(MeterRegistry.class).to(PrometheusMeterRegistry.class);
        expose(MeterRegistry.class);
        expose(PrometheusMeterRegistry.class);
    }

    @Provides
    @Singleton
    static PrometheusMeterRegistry providePrometheusMeterRegistry() {
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }

    public static final class Builder implements TypedBuilder<Module> {
        private BindingSpec<Integer> listenPortSpec = literally(9101);

        public Builder withListenPort(BindingSpec<Integer> listenPortSpec) {
            this.listenPortSpec = checkNotNull(listenPortSpec);
            return this;
        }

        @Override
        public Module build() {
            return new MetricsModule(listenPortSpec);
        }
    }
}
