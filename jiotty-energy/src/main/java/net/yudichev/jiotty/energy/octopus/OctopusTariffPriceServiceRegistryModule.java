package net.yudichev.jiotty.energy.octopus;

import com.google.inject.Key;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.async.JobScheduler;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.connector.octopusenergy.OctopusEnergy;
import net.yudichev.jiotty.timeseriescache.TimeSeriesCache;
import org.jspecify.annotations.Nullable;

import java.time.Duration;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static net.yudichev.jiotty.energy.octopus.Bindings.ExecutorProvider;
import static net.yudichev.jiotty.energy.octopus.Bindings.ForwardWindow;

/// Exposes an [OctopusTariffPriceServiceRegistry] whose price services run on an executor of their own. One registry is meant to serve every account, so that
/// the accounts on a tariff share its retrieval. Requires [OctopusEnergy], [TimeSeriesCache], [JobScheduler] and [CurrentDateTimeProvider] in the parent
/// injector.
public final class OctopusTariffPriceServiceRegistryModule extends BaseExposedKeyModule<OctopusTariffPriceServiceRegistry> {
    private final BindingSpec<Duration> forwardWindowSpec;

    /// @param forwardWindowSpec the value of [Builder#setForwardWindow]; `null` when that was not called, which this constructor rejects
    private OctopusTariffPriceServiceRegistryModule(SpecifiedAnnotation specifiedAnnotation, @Nullable BindingSpec<Duration> forwardWindowSpec) {
        super(specifiedAnnotation);
        this.forwardWindowSpec = checkNotNull(forwardWindowSpec, "setForwardWindow was not called");
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        installLifecycleComponentModule(ExecutorProviderModule.builder()
                                                              .setThreadName(literally("OctopusTariffPrices"))
                                                              .withAnnotation(forAnnotation(ExecutorProvider.class))
                                                              .build());
        forwardWindowSpec.bind(Duration.class).annotatedWith(ForwardWindow.class).installedBy(this::installLifecycleComponentModule);
        install(new FactoryModuleBuilder().build(OctopusTariffPriceServiceFactory.class));
        Key<OctopusTariffPriceServiceRegistry> implKey = registerLifecycleComponent(OctopusTariffPriceServiceRegistry.class);
        // The registry has no separate interface, so an unannotated caller's exposed key is the registry's own key, and Guice rejects binding that key to
        // itself.
        if (!exposedKey.equals(implKey)) {
            bind(exposedKey).to(implKey);
        }
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<OctopusTariffPriceServiceRegistry, Builder> {
        private @Nullable BindingSpec<Duration> forwardWindowSpec;

        /// Sets how far ahead of now the price service of a tariff whose rates Octopus publish ahead, such as Flexible, Go or Cosy, fetches its prices.
        /// This setting is required, and its value must be positive. Agile prices reach as far as Octopus have published them, whatever the value of this
        /// setting.
        public Builder setForwardWindow(BindingSpec<Duration> forwardWindowSpec) {
            this.forwardWindowSpec = checkNotNull(forwardWindowSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<OctopusTariffPriceServiceRegistry> build() {
            return new OctopusTariffPriceServiceRegistryModule(specifiedAnnotation(), forwardWindowSpec);
        }
    }
}
