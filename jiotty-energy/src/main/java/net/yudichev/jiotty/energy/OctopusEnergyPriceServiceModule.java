package net.yudichev.jiotty.energy;

import com.google.inject.Key;
import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
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
import static net.yudichev.jiotty.common.inject.BindingSpec.annotatedWith;
import static net.yudichev.jiotty.common.inject.BindingSpec.exposedBy;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static net.yudichev.jiotty.energy.Bindings.ExecutorProvider;

/// Account-scoped module that installs the [OctopusEnergyProviderService] and exposes it as [EnergyProviderService]. Requires
/// [OctopusAgilePriceServiceRegistry] and [AgilePredictPriceServiceRegistry] (plus [OctopusEnergy], [CurrentDateTimeProvider]) to be present in the parent
/// injector.
public final class OctopusEnergyPriceServiceModule extends BaseLifecycleComponentModule implements ExposedKeyModule<EnergyProviderService> {
    private final Key<EnergyProviderService> exposedKey;
    private final BindingSpec<String> octopusApiKeySpec;
    private final BindingSpec<String> octopusAccountIdSpec;
    private final BindingSpec<SchedulingExecutor> executorSpec;

    private OctopusEnergyPriceServiceModule(SpecifiedAnnotation specifiedAnnotation,
                                            BindingSpec<String> octopusApiKeySpec,
                                            BindingSpec<String> octopusAccountIdSpec,
                                            BindingSpec<SchedulingExecutor> executorSpec) {
        exposedKey = checkNotNull(specifiedAnnotation).specify(ExposedKeyModule.super.getExposedKey().getTypeLiteral());
        this.octopusApiKeySpec = checkNotNull(octopusApiKeySpec);
        this.octopusAccountIdSpec = checkNotNull(octopusAccountIdSpec);
        this.executorSpec = checkNotNull(executorSpec);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Key<EnergyProviderService> getExposedKey() {
        return exposedKey;
    }

    @Override
    protected void configure() {
        executorSpec.bind(SchedulingExecutor.class).annotatedWith(ExecutorProvider.class).installedBy(this::installLifecycleComponentModule);
        // Retry the account fetch with exponential backoff capped at half the poll interval (≈6h) — long enough to ride out lengthy Octopus outages without
        // leaving the user stuck for the full ACCOUNT_POLL_INTERVAL between polls. Predicate always returns true: permanent 401/403 failures surface via
        // OctopusAccountService.subscribeToAuthState (per Stage C task 8), so wasting one backoff window on those is acceptable and avoids a custom predicate.
        var backoffConfig = BackOffConfig.builder()
                                         .setInitialInterval(Duration.ofSeconds(5))
                                         .setMaxInterval(Duration.ofMinutes(30))
                                         .setMaxElapsedTime(Duration.ofHours(6))
                                         .build();
        var pollRetryHandler = exposedBy(BackingOffExceptionHandlerModule.builder()
                                                                         .setRetryableExceptionPredicate(literally(_ -> true))
                                                                         .withConfig(literally(backoffConfig))
                                                                         .build());
        installLifecycleComponentModule(RetryableOperationExecutorModule.builder()
                                                                        .withAnnotation(forAnnotation(OctopusEnergyProviderService.PollRetry.class))
                                                                        .setBackingOffExceptionHandler(pollRetryHandler)
                                                                        .withExecutor(annotatedWith(ExecutorProvider.class)).build());
        // Separate, short-window executor for the on-demand query* calls: ride out a momentary Octopus 5xx / network blip, but give up within seconds (an
        // interactive caller can't be left waiting) and never retry a permanent 4xx — hence the transient-only predicate, unlike the always-true poll
        // predicate above.
        var queryBackoffConfig = BackOffConfig.builder()
                                              .setInitialInterval(Duration.ofSeconds(1))
                                              .setMaxInterval(Duration.ofSeconds(8))
                                              .setMaxElapsedTime(Duration.ofSeconds(30))
                                              .build();
        var queryRetryHandler = exposedBy(
                BackingOffExceptionHandlerModule.builder()
                                                .setRetryableExceptionPredicate(literally(OctopusEnergyProviderService::isTransientFailure))
                                                .withConfig(literally(queryBackoffConfig))
                                                .build());
        installLifecycleComponentModule(RetryableOperationExecutorModule.builder()
                                                                        .withAnnotation(forAnnotation(OctopusEnergyProviderService.QueryRetry.class))
                                                                        .setBackingOffExceptionHandler(queryRetryHandler)
                                                                        .withExecutor(annotatedWith(ExecutorProvider.class)).build());
        octopusApiKeySpec.bind(String.class)
                         .annotatedWith(OctopusEnergyProviderService.ApiKey.class)
                         .installedBy(this::installLifecycleComponentModule);
        octopusAccountIdSpec.bind(String.class)
                            .annotatedWith(OctopusEnergyProviderService.AccountId.class)
                            .installedBy(this::installLifecycleComponentModule);
        bind(exposedKey).to(registerLifecycleComponent(OctopusEnergyProviderService.class));
        expose(exposedKey);
    }

    public static final class Builder implements TypedBuilder<ExposedKeyModule<EnergyProviderService>>, HasWithAnnotation {
        private SpecifiedAnnotation specifiedAnnotation = SpecifiedAnnotation.forNoAnnotation();
        private BindingSpec<String> octopusApiKeySpec;
        private BindingSpec<String> octopusAccountIdSpec;
        private BindingSpec<SchedulingExecutor> executorSpec = exposedBy(ExecutorProviderModule.builder()
                                                                                               .setThreadName(literally("Energy"))
                                                                                               .withAnnotation(forAnnotation(uniqueAnnotation()))
                                                                                               .build());

        public Builder setOctopusAccountId(BindingSpec<String> octopusAccountIdSpec) {
            this.octopusAccountIdSpec = checkNotNull(octopusAccountIdSpec);
            return this;
        }

        public Builder setOctopusApiKey(BindingSpec<String> octopusApiKeySpec) {
            this.octopusApiKeySpec = octopusApiKeySpec;
            return this;
        }

        /// Reuses the specified executor for the service poll and retries. If not specified, uses own dedicated thread.
        public Builder withExecutor(BindingSpec<SchedulingExecutor> executorSpec) {
            this.executorSpec = checkNotNull(executorSpec);
            return this;
        }

        @Override
        public Builder withAnnotation(SpecifiedAnnotation specifiedAnnotation) {
            this.specifiedAnnotation = checkNotNull(specifiedAnnotation);
            return this;
        }

        @Override
        public ExposedKeyModule<EnergyProviderService> build() {
            return new OctopusEnergyPriceServiceModule(specifiedAnnotation, octopusApiKeySpec, octopusAccountIdSpec, executorSpec);
        }
    }
}
