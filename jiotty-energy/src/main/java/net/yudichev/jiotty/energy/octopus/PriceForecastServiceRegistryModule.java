package net.yudichev.jiotty.energy.octopus;

import com.google.common.reflect.TypeToken;
import com.google.inject.Key;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.async.backoff.BackOffConfig;
import net.yudichev.jiotty.common.async.backoff.BackingOffExceptionHandlerModule;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutorModule;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.metrics.NoopMeterRegistry;
import net.yudichev.jiotty.common.misc.LoggingUpstreamHealthHandler;
import net.yudichev.jiotty.common.misc.UpstreamHealthHandler;
import net.yudichev.jiotty.connector.octopusenergy.priceforecast.PriceForecastSourcesModule;
import net.yudichev.jiotty.persistence.varstore.VarStore;

import java.time.Duration;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.annotatedWith;
import static net.yudichev.jiotty.common.inject.BindingSpec.exposedBy;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static net.yudichev.jiotty.energy.octopus.Bindings.Dependency;
import static net.yudichev.jiotty.energy.octopus.Bindings.PriceForecast;

/// Exposes a [PriceForecastServiceRegistry] serving forecasts fetched from public Agile-price forecasting services, with its own threading and retry
/// policy. One registry per application: the forecast for a region is the same for every consumer, so the per-region services it hands out are shared.
public final class PriceForecastServiceRegistryModule extends BaseLifecycleComponentModule
        implements ExposedKeyModule<PriceForecastServiceRegistry> {
    private final BindingSpec<UpstreamHealthHandler> statusHandlerSpec;
    private final BindingSpec<MeterRegistry> meterRegistrySpec;
    private final BindingSpec<Optional<VarStore>> varStoreSpec;
    private final Key<PriceForecastServiceRegistry> exposedKey;

    private PriceForecastServiceRegistryModule(SpecifiedAnnotation specifiedAnnotation,
                                               BindingSpec<UpstreamHealthHandler> statusHandlerSpec,
                                               BindingSpec<MeterRegistry> meterRegistrySpec,
                                               BindingSpec<Optional<VarStore>> varStoreSpec) {
        exposedKey = specifiedAnnotation.specify(ExposedKeyModule.super.getExposedKey().getTypeLiteral());
        this.statusHandlerSpec = checkNotNull(statusHandlerSpec);
        this.meterRegistrySpec = checkNotNull(meterRegistrySpec);
        this.varStoreSpec = checkNotNull(varStoreSpec);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Key<PriceForecastServiceRegistry> getExposedKey() {
        return exposedKey;
    }

    @Override
    protected void configure() {
        installLifecycleComponentModule(ExecutorProviderModule.builder()
                                                              .setThreadName(literally("PriceForecast"))
                                                              .withAnnotation(forAnnotation(PriceForecast.class))
                                                              .build());
        installLifecycleComponentModule(PriceForecastSourcesModule.builder().build());
        // Retry the whole failover sweep on any failure: with several heterogeneous sources there is no failure class worth giving up on early, and
        // exhausting this window is the "every source kept failing" signal reported to the health handler. The window ends well before the next refresh
        // is due, so one refresh's retries always conclude before the next refresh starts.
        var sweepBackoffConfig = BackOffConfig.builder()
                                              .setInitialInterval(Duration.ofMinutes(1))
                                              .setMultiplier(2)
                                              .setMaxInterval(Duration.ofMinutes(15))
                                              .setMaxElapsedTime(Duration.ofMinutes(45))
                                              .build();
        installLifecycleComponentModule(RetryableOperationExecutorModule.builder()
                                                                        .setBackingOffExceptionHandler(exposedBy(
                                                                                BackingOffExceptionHandlerModule.builder()
                                                                                                                .setRetryableExceptionPredicate(
                                                                                                                        literally(_ -> true))
                                                                                                                .withConfig(literally(sweepBackoffConfig))
                                                                                                                .build()))
                                                                        .withExecutor(annotatedWith(PriceForecast.class))
                                                                        .withAnnotation(forAnnotation(Dependency.class))
                                                                        .build());
        // Singleton: health is per upstream, so every per-region service reports into one handler.
        statusHandlerSpec.bind(UpstreamHealthHandler.class)
                         .annotatedWith(Dependency.class)
                         .in(Singleton.class)
                         .installedBy(this::installLifecycleComponentModule);
        meterRegistrySpec.bind(MeterRegistry.class)
                         .annotatedWith(Dependency.class)
                         .installedBy(this::installLifecycleComponentModule);
        varStoreSpec.bind(new TypeLiteral<>() {})
                    .annotatedWith(Dependency.class)
                    .installedBy(this::installLifecycleComponentModule);
        install(new FactoryModuleBuilder().build(ForecastEnergyPriceServiceImpl.Factory.class));
        bind(exposedKey).to(registerLifecycleComponent(PriceForecastServiceRegistryImpl.class));
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<PriceForecastServiceRegistry, Builder> {
        private BindingSpec<UpstreamHealthHandler> statusHandlerSpec = literally(new LoggingUpstreamHealthHandler("Price forecast retrieval"));
        private BindingSpec<MeterRegistry> meterRegistrySpec = literally(new NoopMeterRegistry());
        private BindingSpec<Optional<VarStore>> varStoreSpec = literally(Optional.empty());

        public Builder withStatusHandler(BindingSpec<UpstreamHealthHandler> statusHandlerSpec) {
            this.statusHandlerSpec = checkNotNull(statusHandlerSpec);
            return this;
        }

        /// Sets the [MeterRegistry] the per-region services count their per-source fetch attempts with. Defaults to a [NoopMeterRegistry] (unmetered).
        public Builder withMeterRegistry(BindingSpec<MeterRegistry> meterRegistrySpec) {
            this.meterRegistrySpec = checkNotNull(meterRegistrySpec);
            return this;
        }

        /// Sets the [VarStore] each per-region service persists its latest forecast in, so a restart serves the stored forecast until the first fetch
        /// succeeds.
        public Builder withVarStore(BindingSpec<VarStore> varStoreSpec) {
            this.varStoreSpec = varStoreSpec.map(new TypeToken<>() {}, new TypeToken<>() {}, Optional::of);
            return this;
        }

        @Override
        public ExposedKeyModule<PriceForecastServiceRegistry> build() {
            return new PriceForecastServiceRegistryModule(specifiedAnnotation(), statusHandlerSpec, meterRegistrySpec, varStoreSpec);
        }
    }
}
