package net.yudichev.jiotty.energy;

import com.google.inject.Key;
import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.async.backoff.BackOffConfig;
import net.yudichev.jiotty.common.async.backoff.BackingOffExceptionHandlerModule;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutorModule;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.HasWithAnnotation;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.lang.TypedBuilder;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.connector.octopusenergy.OctopusEnergy;

import java.time.Duration;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.exposedBy;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static net.yudichev.jiotty.energy.Bindings.ExecutorProvider;

/// User-scope module that installs the [OctopusAgileEnergyPriceServiceResolver] and exposes it as [EnergyPriceService]. Requires
/// [OctopusAgilePriceServiceRegistry] and [AgilePredictPriceServiceRegistry] (plus [OctopusEnergy], [CurrentDateTimeProvider]) to be present in the parent
/// injector.
public final class EnergyPriceServiceResolverModule extends BaseLifecycleComponentModule implements ExposedKeyModule<EnergyPriceService> {
    private final Key<EnergyPriceService> exposedKey;
    private final BindingSpec<String> octopusApiKeySpec;
    private final BindingSpec<String> octopusAccountIdSpec;

    private EnergyPriceServiceResolverModule(SpecifiedAnnotation specifiedAnnotation,
                                             BindingSpec<String> octopusApiKeySpec,
                                             BindingSpec<String> octopusAccountIdSpec) {
        exposedKey = checkNotNull(specifiedAnnotation).specify(ExposedKeyModule.super.getExposedKey().getTypeLiteral());
        this.octopusApiKeySpec = checkNotNull(octopusApiKeySpec);
        this.octopusAccountIdSpec = checkNotNull(octopusAccountIdSpec);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Key<EnergyPriceService> getExposedKey() {
        return exposedKey;
    }

    @Override
    protected void configure() {
        installLifecycleComponentModule(ExecutorProviderModule.builder()
                                                              .setThreadName(literally("Prices"))
                                                              .withAnnotation(forAnnotation(ExecutorProvider.class))
                                                              .build());
        // Retry the account fetch with exponential backoff capped at half the resolver's poll interval (≈6h) — long enough to ride out lengthy Octopus outages
        // without leaving the user stuck for the full TARIFF_POLL_INTERVAL between polls. Predicate always returns true: permanent 401/403 failures surface via
        // OctopusAccountService.subscribeToAuthState (per Stage C task 8), so wasting one backoff window on those is acceptable and avoids a custom predicate.
        var backoffConfig = BackOffConfig.builder()
                                         .setInitialInterval(Duration.ofSeconds(5))
                                         .setMaxInterval(Duration.ofMinutes(30))
                                         .setMaxElapsedTime(Duration.ofHours(6))
                                         .build();
        installLifecycleComponentModule(
                RetryableOperationExecutorModule.builder()
                                                .setBackingOffExceptionHandler(exposedBy(
                                                        BackingOffExceptionHandlerModule.builder()
                                                                                        .setRetryableExceptionPredicate(literally(_ -> true))
                                                                                        .withConfig(literally(backoffConfig))
                                                                                        .build()))
                                                .build());
        octopusApiKeySpec.bind(String.class)
                         .annotatedWith(OctopusAccountContext.ApiKey.class)
                         .installedBy(this::installLifecycleComponentModule);
        octopusAccountIdSpec.bind(String.class)
                            .annotatedWith(OctopusAccountContext.AccountId.class)
                            .installedBy(this::installLifecycleComponentModule);
        // Register the account context before the resolver: the resolver subscribes to the context in its doStart, so the context must start (and thus be
        // started-and-not-lifecycling) first.
        registerLifecycleComponent(OctopusAccountContext.class);
        bind(exposedKey).to(registerLifecycleComponent(OctopusAgileEnergyPriceServiceResolver.class));
        expose(exposedKey);
    }

    public static final class Builder implements TypedBuilder<ExposedKeyModule<EnergyPriceService>>, HasWithAnnotation {
        private SpecifiedAnnotation specifiedAnnotation = SpecifiedAnnotation.forNoAnnotation();
        private BindingSpec<String> octopusApiKeySpec;
        private BindingSpec<String> octopusAccountIdSpec;

        public Builder setOctopusAccountId(BindingSpec<String> octopusAccountIdSpec) {
            this.octopusAccountIdSpec = checkNotNull(octopusAccountIdSpec);
            return this;
        }

        public Builder setOctopusApiKey(BindingSpec<String> octopusApiKeySpec) {
            this.octopusApiKeySpec = octopusApiKeySpec;
            return this;
        }

        @Override
        public Builder withAnnotation(SpecifiedAnnotation specifiedAnnotation) {
            this.specifiedAnnotation = checkNotNull(specifiedAnnotation);
            return this;
        }

        @Override
        public ExposedKeyModule<EnergyPriceService> build() {
            return new EnergyPriceServiceResolverModule(specifiedAnnotation, octopusApiKeySpec, octopusAccountIdSpec);
        }
    }
}
