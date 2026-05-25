package net.yudichev.jiotty.energy;

import com.google.inject.assistedinject.FactoryModuleBuilder;
import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.connector.octopusenergy.agilepredict.AgilePredictPriceModule;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static net.yudichev.jiotty.energy.Bindings.AgilePredict;

/// App-scope module that installs the [AgilePredictPriceServiceRegistry] together with the AgilePredict connector, its own executor, and the
/// [AgilePredictEnergyPriceServiceImpl.Factory] assisted-inject factory used by the registry to construct per-region impls.
public final class AgilePredictPriceServiceRegistryModule extends BaseLifecycleComponentModule
        implements ExposedKeyModule<AgilePredictPriceServiceRegistry> {

    @Override
    protected void configure() {
        installLifecycleComponentModule(ExecutorProviderModule.builder()
                                                              .setThreadName(literally("AgilePredictPrices"))
                                                              .withAnnotation(forAnnotation(AgilePredict.class))
                                                              .build());
        installLifecycleComponentModule(new AgilePredictPriceModule());
        install(new FactoryModuleBuilder()
                        .implement(AgilePredictEnergyPriceService.class, AgilePredictEnergyPriceServiceImpl.class)
                        .build(AgilePredictEnergyPriceServiceImpl.Factory.class));
        registerLifecycleComponent(AgilePredictPriceServiceRegistry.class);
        expose(getExposedKey());
    }
}
