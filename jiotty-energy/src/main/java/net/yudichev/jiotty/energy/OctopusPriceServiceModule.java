package net.yudichev.jiotty.energy;

import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.connector.octopusenergy.OctopusEnergyModule;
import net.yudichev.jiotty.connector.octopusenergy.agilepredict.AgilePredictPriceModule;

import static net.yudichev.jiotty.common.keystore.KeyStoreEntryModule.keyStoreEntry;

public final class OctopusPriceServiceModule extends BaseLifecycleComponentModule implements ExposedKeyModule<EnergyPriceService> {
    @Override
    protected void configure() {
        installLifecycleComponentModule(new ExecutorProviderModule("Prices", Bindings.ExecutorProvider.class));
        installLifecycleComponentModule(OctopusEnergyModule.builder()
                                                           // TODO:commerce these are user options
                                                           .setApiKey(keyStoreEntry("octopus-api-key"))
                                                           .setAccountId(keyStoreEntry("octopus-account"))
                                                           .build());
        bind(EnergyPriceService.class).annotatedWith(Bindings.Octopus.class).to(registerLifecycleComponent(OctopusEnergyPriceServiceImpl.class));

        installLifecycleComponentModule(new AgilePredictPriceModule());
        bind(EnergyPriceService.class).annotatedWith(Bindings.AgilePredict.class).to(registerLifecycleComponent(AgilePredictEnergyPriceServiceImpl.class));

        bind(getExposedKey()).to(RealAndPredictedPriceService.class);
        expose(getExposedKey());
    }
}
