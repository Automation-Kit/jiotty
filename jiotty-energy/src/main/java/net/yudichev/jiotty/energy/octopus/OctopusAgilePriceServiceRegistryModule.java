package net.yudichev.jiotty.energy.octopus;

import com.google.inject.assistedinject.FactoryModuleBuilder;
import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.async.JobScheduler;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.connector.octopusenergy.OctopusEnergy;
import net.yudichev.jiotty.timeseriescache.TimeSeriesCache;

import static net.yudichev.jiotty.common.inject.BaseModuleBuilder.simpleBuilder;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static net.yudichev.jiotty.energy.octopus.Bindings.ExecutorProvider;

/// App-scope module that installs the [OctopusAgilePriceServiceRegistry], its private executor, and the [OctopusAgilePriceService.Factory] assisted-inject
/// factory used by the registry to construct per-tariff impls. Requires [OctopusEnergy], [TimeSeriesCache], [JobScheduler] and [CurrentDateTimeProvider] to be
/// present in the parent injector — install those at app-scope before installing this module.
public final class OctopusAgilePriceServiceRegistryModule extends BaseExposedKeyModule<OctopusAgilePriceServiceRegistry> {
    private OctopusAgilePriceServiceRegistryModule(SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
    }

    public static BaseModuleBuilder<OctopusAgilePriceServiceRegistry, ?> builder() {
        return simpleBuilder(OctopusAgilePriceServiceRegistryModule::new);
    }

    @Override
    protected void configure() {
        installLifecycleComponentModule(ExecutorProviderModule.builder()
                                                              .setThreadName(literally("OctopusAgilePrices"))
                                                              .withAnnotation(forAnnotation(ExecutorProvider.class))
                                                              .build());
        install(new FactoryModuleBuilder()
                        .implement(OctopusAgilePriceService.class, OctopusAgilePriceServiceImpl.class)
                        .build(OctopusAgilePriceService.Factory.class));
        var implKey = registerLifecycleComponent(OctopusAgilePriceServiceRegistry.class);
        // The registry is a concrete class rather than an interface/impl pair, so an unannotated caller's exposed key IS the impl key; aliasing it to itself
        // is what Guice rejects as a binding pointing to itself.
        if (!exposedKey.equals(implKey)) {
            bind(exposedKey).to(implKey);
        }
        expose(exposedKey);
    }
}
