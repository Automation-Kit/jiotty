package net.yudichev.jiotty.energy.octopus;

import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.backoff.BackOffConfig;
import net.yudichev.jiotty.common.async.backoff.BackingOffExceptionHandlerModule;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutorModule;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountService;
import net.yudichev.jiotty.connector.octopusenergy.OctopusEnergy;
import org.jspecify.annotations.Nullable;

import java.time.Duration;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.annotatedWith;
import static net.yudichev.jiotty.common.inject.BindingSpec.exposedBy;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static net.yudichev.jiotty.energy.octopus.Bindings.ExecutorProvider;

/// Account-scoped module that installs the [OctopusEnergyProviderService] and exposes it as [EnergyProviderService]. Requires
/// [OctopusAgilePriceServiceRegistry] and [PriceForecastServiceRegistry] (plus [OctopusEnergy], [CurrentDateTimeProvider]) to be present in the parent
/// injector.
public final class OctopusEnergyPriceServiceModule extends BaseExposedKeyModule<EnergyProviderService> {
    private final @Nullable BindingSpec<String> octopusApiKeySpec;
    private final @Nullable BindingSpec<String> octopusAccountIdSpec;
    private final @Nullable BindingSpec<OctopusAccountService> accountServiceSpec;
    private final BindingSpec<SchedulingExecutor> executorSpec;

    /// @param octopusApiKeySpec    the Octopus account's API key, or `null` when `accountServiceSpec` supplies the account
    /// @param octopusAccountIdSpec the Octopus account's id, or `null` when `accountServiceSpec` supplies the account
    /// @param accountServiceSpec   the Octopus account, or `null` when the module opens the account from `octopusApiKeySpec` and `octopusAccountIdSpec`
    private OctopusEnergyPriceServiceModule(SpecifiedAnnotation specifiedAnnotation,
                                            @Nullable BindingSpec<String> octopusApiKeySpec,
                                            @Nullable BindingSpec<String> octopusAccountIdSpec,
                                            @Nullable BindingSpec<OctopusAccountService> accountServiceSpec,
                                            BindingSpec<SchedulingExecutor> executorSpec) {
        super(specifiedAnnotation);
        checkArgument(accountServiceSpec == null
                      ? octopusApiKeySpec != null && octopusAccountIdSpec != null
                      : octopusApiKeySpec == null && octopusAccountIdSpec == null,
                      "set either both Octopus credentials or the account service, not both");
        this.octopusApiKeySpec = octopusApiKeySpec;
        this.octopusAccountIdSpec = octopusAccountIdSpec;
        this.accountServiceSpec = accountServiceSpec;
        this.executorSpec = checkNotNull(executorSpec);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        executorSpec.bind(SchedulingExecutor.class).annotatedWith(ExecutorProvider.class).installedBy(this::installLifecycleComponentModule);
        // Retry the account fetch with exponential backoff capped at half the poll interval (≈6h) — long enough to ride out lengthy Octopus outages without
        // leaving the user stuck for the full ACCOUNT_POLL_INTERVAL between polls. Predicate always returns true: permanent 401/403 failures surface via
        // OctopusAccountService.subscribeToAuthState, so wasting one backoff window on those is acceptable. This deliberately layers over the Octopus
        // connector's own shared-outage retry: the connector smooths transient blips over minutes, this window keeps the background poll alive over hours.
        // The interactive query* calls rely on the connector's retry alone.
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
        if (accountServiceSpec == null) {
            assert octopusApiKeySpec != null && octopusAccountIdSpec != null : "the constructor admits only one of the two account sources";
            octopusApiKeySpec.bind(String.class)
                             .annotatedWith(CredentialsOctopusAccountServiceProvider.ApiKey.class)
                             .installedBy(this::installLifecycleComponentModule);
            octopusAccountIdSpec.bind(String.class)
                                .annotatedWith(CredentialsOctopusAccountServiceProvider.AccountId.class)
                                .installedBy(this::installLifecycleComponentModule);
            bind(OctopusAccountService.class).annotatedWith(OctopusEnergyProviderService.Dependency.class)
                                             .toProvider(CredentialsOctopusAccountServiceProvider.class);
        } else {
            accountServiceSpec.bind(OctopusAccountService.class)
                              .annotatedWith(OctopusEnergyProviderService.Dependency.class)
                              .installedBy(this::installLifecycleComponentModule);
        }
        bind(exposedKey).to(registerLifecycleComponent(OctopusEnergyProviderService.class));
        expose(exposedKey);
    }

    /// The account must be supplied either as the credentials of a real Octopus account or as an [OctopusAccountService], and not both.
    public static final class Builder extends BaseModuleBuilder<EnergyProviderService, Builder> {
        private @Nullable BindingSpec<String> octopusApiKeySpec;
        private @Nullable BindingSpec<String> octopusAccountIdSpec;
        private @Nullable BindingSpec<OctopusAccountService> accountServiceSpec;
        private BindingSpec<SchedulingExecutor> executorSpec = exposedBy(ExecutorProviderModule.builder()
                                                                                               .setThreadName(literally("Energy"))
                                                                                               .withAnnotation(forAnnotation(uniqueAnnotation()))
                                                                                               .build());

        public Builder setOctopusAccountId(BindingSpec<String> octopusAccountIdSpec) {
            this.octopusAccountIdSpec = checkNotNull(octopusAccountIdSpec);
            return this;
        }

        public Builder setOctopusApiKey(BindingSpec<String> octopusApiKeySpec) {
            this.octopusApiKeySpec = checkNotNull(octopusApiKeySpec);
            return this;
        }

        /// Supplies the [OctopusAccountService] directly, in place of the credentials. [OctopusEnergyProviderService] obtains one from the binding on every
        /// start and closes it on stop, so the binding must provide a new instance each time.
        public Builder setAccountService(BindingSpec<OctopusAccountService> accountServiceSpec) {
            this.accountServiceSpec = checkNotNull(accountServiceSpec);
            return this;
        }

        /// Reuses the specified executor for the service poll and retries. If not specified, uses own dedicated thread.
        public Builder withExecutor(BindingSpec<SchedulingExecutor> executorSpec) {
            this.executorSpec = checkNotNull(executorSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<EnergyProviderService> build() {
            return new OctopusEnergyPriceServiceModule(specifiedAnnotation(), octopusApiKeySpec, octopusAccountIdSpec, accountServiceSpec, executorSpec);
        }
    }
}
