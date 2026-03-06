package net.yudichev.jiotty.energy;

import com.google.inject.Key;
import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.HasWithAnnotation;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.lang.TypedBuilder;
import net.yudichev.jiotty.connector.octopusenergy.OctopusEnergyModule;
import net.yudichev.jiotty.connector.octopusenergy.agilepredict.AgilePredictPriceModule;

import java.time.ZoneId;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.keystore.KeyStoreEntryModule.keyStoreEntry;
import static net.yudichev.jiotty.energy.Bindings.AgilePredict;
import static net.yudichev.jiotty.energy.Bindings.Dependency;
import static net.yudichev.jiotty.energy.Bindings.ExecutorProvider;
import static net.yudichev.jiotty.energy.Bindings.Octopus;

public final class OctopusPriceServiceModule extends BaseLifecycleComponentModule implements ExposedKeyModule<EnergyPriceService> {
    private final Key<EnergyPriceService> exposedKey;
    private final BindingSpec<ZoneId> zoneIdSpec;

    private OctopusPriceServiceModule(SpecifiedAnnotation specifiedAnnotation, BindingSpec<ZoneId> zoneIdSpec) {
        exposedKey = checkNotNull(specifiedAnnotation).specify(ExposedKeyModule.super.getExposedKey().getTypeLiteral());
        this.zoneIdSpec = checkNotNull(zoneIdSpec);
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
        zoneIdSpec.bind(ZoneId.class).annotatedWith(Dependency.class).installedBy(this::installLifecycleComponentModule);
        installLifecycleComponentModule(new ExecutorProviderModule("Prices", ExecutorProvider.class));
        installLifecycleComponentModule(OctopusEnergyModule.builder()
                                                           // TODO:commerce these are user options
                                                           .setApiKey(keyStoreEntry("octopus-api-key"))
                                                           .setAccountId(keyStoreEntry("octopus-account"))
                                                           .build());
        bind(EnergyPriceService.class).annotatedWith(Octopus.class).to(registerLifecycleComponent(OctopusEnergyPriceServiceImpl.class));

        installLifecycleComponentModule(new AgilePredictPriceModule());
        bind(EnergyPriceService.class).annotatedWith(AgilePredict.class).to(registerLifecycleComponent(AgilePredictEnergyPriceServiceImpl.class));

        bind(getExposedKey()).to(RealAndPredictedPriceService.class);
        expose(getExposedKey());
    }

    public static final class Builder implements TypedBuilder<ExposedKeyModule<EnergyPriceService>>, HasWithAnnotation {
        private SpecifiedAnnotation specifiedAnnotation = SpecifiedAnnotation.forNoAnnotation();
        private BindingSpec<ZoneId> zoneIdSpec = BindingSpec.boundTo(ZoneId.class);

        @Override
        public Builder withAnnotation(SpecifiedAnnotation specifiedAnnotation) {
            this.specifiedAnnotation = checkNotNull(specifiedAnnotation);
            return this;
        }

        public Builder withZoneId(BindingSpec<ZoneId> zoneIdSpec) {
            this.zoneIdSpec = checkNotNull(zoneIdSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<EnergyPriceService> build() {
            return new OctopusPriceServiceModule(specifiedAnnotation, zoneIdSpec);
        }
    }
}
