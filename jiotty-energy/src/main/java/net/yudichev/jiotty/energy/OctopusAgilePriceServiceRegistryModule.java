package net.yudichev.jiotty.energy;

import com.google.inject.assistedinject.FactoryModuleBuilder;
import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.async.JobScheduler;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.connector.octopusenergy.OctopusEnergy;
import net.yudichev.jiotty.timeseriescache.TimeSeriesCache;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static net.yudichev.jiotty.energy.Bindings.ExecutorProvider;

/// App-scope module that installs the [OctopusAgilePriceServiceRegistry], its private executor, and the [OctopusAgilePriceServiceImpl.Factory] assisted-inject
/// factory used by the registry to construct per-tariff impls. Requires [OctopusEnergy], [TimeSeriesCache], [JobScheduler] and [CurrentDateTimeProvider] to be
/// present in the parent injector — install those at app-scope before installing this module.
public final class OctopusAgilePriceServiceRegistryModule extends BaseLifecycleComponentModule
        implements ExposedKeyModule<OctopusAgilePriceServiceRegistry> {

    @Override
    protected void configure() {
        installLifecycleComponentModule(ExecutorProviderModule.builder()
                                                              .setThreadName(literally("OctopusAgilePrices"))
                                                              .withAnnotation(forAnnotation(ExecutorProvider.class))
                                                              .build());
        install(new FactoryModuleBuilder()
                        .implement(OctopusAgilePriceService.class, OctopusAgilePriceServiceImpl.class)
                        .build(OctopusAgilePriceServiceImpl.Factory.class));
        registerLifecycleComponent(OctopusAgilePriceServiceRegistry.class);
        expose(getExposedKey());
    }
}
