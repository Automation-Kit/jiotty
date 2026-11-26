package net.yudichev.jiotty.energy.octopus.provider;

import com.google.inject.assistedinject.FactoryModuleBuilder;
import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.backoff.BackOffConfig;
import net.yudichev.jiotty.common.async.backoff.BackingOffExceptionHandlerModule;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutorModule;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.Bindings.Rng;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountService;
import net.yudichev.jiotty.connector.octopusenergy.OctopusEnergy;
import net.yudichev.jiotty.energy.octopus.EnergyProviderService;
import net.yudichev.jiotty.energy.octopus.OctopusTariffPriceServiceRegistry;
import net.yudichev.jiotty.energy.octopus.OctopusTariffPriceServiceRegistryModule;
import net.yudichev.jiotty.energy.octopus.PriceForecastServiceRegistry;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.function.DoubleSupplier;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.annotatedWith;
import static net.yudichev.jiotty.common.inject.BindingSpec.exposedBy;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static net.yudichev.jiotty.energy.octopus.Bindings.ExecutorProvider;
import static net.yudichev.jiotty.energy.octopus.Bindings.ForwardWindow;

/// Exposes an [EnergyProviderService] that serves the prices and account details of one Octopus account. Requires [OctopusTariffPriceServiceRegistry] and
/// [PriceForecastServiceRegistry] (plus [OctopusEnergy], [CurrentDateTimeProvider] and a [Rng] [DoubleSupplier]) to be present in the parent injector.
public final class OctopusEnergyPriceServiceModule extends BaseExposedKeyModule<EnergyProviderService> {
    private final @Nullable BindingSpec<String> octopusApiKeySpec;
    private final @Nullable BindingSpec<String> octopusAccountIdSpec;
    private final @Nullable BindingSpec<OctopusAccountService> accountServiceSpec;
    private final BindingSpec<SchedulingExecutor> executorSpec;
    private final BindingSpec<Duration> accountPollIntervalSpec;
    private final BindingSpec<Duration> forwardWindowSpec;

    /// @param octopusApiKeySpec       the Octopus account's API key, or `null` when `accountServiceSpec` supplies the account
    /// @param octopusAccountIdSpec    the Octopus account's id, or `null` when `accountServiceSpec` supplies the account
    /// @param accountServiceSpec      the Octopus account, or `null` when the module opens the account from `octopusApiKeySpec` and `octopusAccountIdSpec`
    /// @param accountPollIntervalSpec the interval between account fetches
    /// @param forwardWindowSpec       the period ahead of now whose agreements are joined into the prices; `null` when [Builder#setForwardWindow] was not
    ///                                called, which this constructor rejects
    private OctopusEnergyPriceServiceModule(SpecifiedAnnotation specifiedAnnotation,
                                            @Nullable BindingSpec<String> octopusApiKeySpec,
                                            @Nullable BindingSpec<String> octopusAccountIdSpec,
                                            @Nullable BindingSpec<OctopusAccountService> accountServiceSpec,
                                            BindingSpec<SchedulingExecutor> executorSpec,
                                            BindingSpec<Duration> accountPollIntervalSpec,
                                            @Nullable BindingSpec<Duration> forwardWindowSpec) {
        super(specifiedAnnotation);
        checkArgument(accountServiceSpec == null
                      ? octopusApiKeySpec != null && octopusAccountIdSpec != null
                      : octopusApiKeySpec == null && octopusAccountIdSpec == null,
                      "set either both Octopus credentials or the account service, not both");
        this.octopusApiKeySpec = octopusApiKeySpec;
        this.octopusAccountIdSpec = octopusAccountIdSpec;
        this.accountServiceSpec = accountServiceSpec;
        this.executorSpec = checkNotNull(executorSpec);
        this.accountPollIntervalSpec = checkNotNull(accountPollIntervalSpec);
        this.forwardWindowSpec = checkNotNull(forwardWindowSpec, "setForwardWindow was not called");
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        executorSpec.bind(SchedulingExecutor.class).annotatedWith(ExecutorProvider.class).installedBy(this::installLifecycleComponentModule);
        accountPollIntervalSpec.bind(Duration.class)
                               .annotatedWith(AccountNodeImpl.AccountPollInterval.class)
                               .installedBy(this::installLifecycleComponentModule);
        forwardWindowSpec.bind(Duration.class).annotatedWith(ForwardWindow.class).installedBy(this::installLifecycleComponentModule);
        // Retry the account fetch with exponential backoff for up to half of DEFAULT_ACCOUNT_POLL_INTERVAL (about 6 hours), long enough to cover a long
        // Octopus outage without leaving the user waiting a whole poll interval. A shorter poll interval keeps this bound, since AccountNodeImpl skips a
        // poll while the previous fetch is still retrying. The predicate, OctopusEnergyProviderService
        // .isRetryablePollFailure, retries every failure except a response that could not be parsed: each attempt fetches the account again, and an
        // unchanged payload fails the same way. The predicate also retries a 401 or 403 response, although OctopusAccountService.subscribeToAuthState
        // reports those separately.
        // This backoff retry sits on top of the Octopus connector's own retry: the connector retries brief failures over minutes, and this retry keeps the
        // background poll going over hours.
        var backoffConfig = BackOffConfig.builder()
                                         .setInitialInterval(Duration.ofSeconds(5))
                                         .setMaxInterval(Duration.ofMinutes(30))
                                         .setMaxElapsedTime(Duration.ofHours(6))
                                         .build();
        var pollRetryHandler = exposedBy(BackingOffExceptionHandlerModule.builder()
                                                                         .setRetryableExceptionPredicate(literally(
                                                                                 OctopusEnergyProviderService::isRetryablePollFailure))
                                                                         .withConfig(literally(backoffConfig))
                                                                         .build());
        installLifecycleComponentModule(RetryableOperationExecutorModule.builder()
                                                                        .withAnnotation(forAnnotation(AccountNodeImpl.PollRetry.class))
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
        install(new FactoryModuleBuilder().build(AccountNodeImpl.Factory.class));
        install(new FactoryModuleBuilder().build(AgreementWindowNodeImpl.Factory.class));
        install(new FactoryModuleBuilder().build(TariffSourcesNodeImpl.Factory.class));
        install(new FactoryModuleBuilder().build(ForecastNodeImpl.Factory.class));
        install(new FactoryModuleBuilder().build(JoinedPricesNodeImpl.Factory.class));
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
        private BindingSpec<Duration> accountPollIntervalSpec = literally(OctopusEnergyProviderService.DEFAULT_ACCOUNT_POLL_INTERVAL);
        private @Nullable BindingSpec<Duration> forwardWindowSpec;

        public Builder setOctopusAccountId(BindingSpec<String> octopusAccountIdSpec) {
            this.octopusAccountIdSpec = checkNotNull(octopusAccountIdSpec);
            return this;
        }

        public Builder setOctopusApiKey(BindingSpec<String> octopusApiKeySpec) {
            this.octopusApiKeySpec = checkNotNull(octopusApiKeySpec);
            return this;
        }

        /// Supplies the [OctopusAccountService] directly, in place of the credentials. The exposed service obtains an [OctopusAccountService] from the
        /// binding on every start and closes it on stop, so the binding must provide a new instance each time.
        public Builder setAccountService(BindingSpec<OctopusAccountService> accountServiceSpec) {
            this.accountServiceSpec = checkNotNull(accountServiceSpec);
            return this;
        }

        /// Sets how far ahead of now the agreements that are joined into the prices may start. This setting is required, and its value must be positive. The
        /// prices of a tariff reach only as far as the value set with [OctopusTariffPriceServiceRegistryModule.Builder#setForwardWindow], so pass the same
        /// value to both.
        public Builder setForwardWindow(BindingSpec<Duration> forwardWindowSpec) {
            this.forwardWindowSpec = checkNotNull(forwardWindowSpec);
            return this;
        }

        /// Reuses the specified executor for the service poll and retries. If not specified, uses own dedicated thread.
        public Builder withExecutor(BindingSpec<SchedulingExecutor> executorSpec) {
            this.executorSpec = checkNotNull(executorSpec);
            return this;
        }

        /// Sets the interval between account fetches, 12 hours by default. A shorter interval makes a change to the account's agreements reach the prices
        /// sooner; a poll is skipped while the previous fetch is still retrying.
        public Builder withAccountPollInterval(BindingSpec<Duration> accountPollIntervalSpec) {
            this.accountPollIntervalSpec = checkNotNull(accountPollIntervalSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<EnergyProviderService> build() {
            return new OctopusEnergyPriceServiceModule(specifiedAnnotation(),
                                                       octopusApiKeySpec,
                                                       octopusAccountIdSpec,
                                                       accountServiceSpec,
                                                       executorSpec,
                                                       accountPollIntervalSpec,
                                                       forwardWindowSpec);
        }
    }
}
