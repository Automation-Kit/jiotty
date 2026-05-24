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
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static net.yudichev.jiotty.energy.Bindings.AgilePredict;
import static net.yudichev.jiotty.energy.Bindings.Dependency;
import static net.yudichev.jiotty.energy.Bindings.ExecutorProvider;
import static net.yudichev.jiotty.energy.Bindings.Octopus;

public final class OctopusAgilePriceServiceModule extends BaseLifecycleComponentModule implements ExposedKeyModule<EnergyPriceService> {
    private final Key<EnergyPriceService> exposedKey;
    private final BindingSpec<ZoneId> zoneIdSpec;
    private final BindingSpec<String> octopusApiKeySpec;
    private final BindingSpec<String> octopusAccountId;

    private OctopusAgilePriceServiceModule(SpecifiedAnnotation specifiedAnnotation,
                                      BindingSpec<ZoneId> zoneIdSpec,
                                      BindingSpec<String> octopusApiKeySpec,
                                      BindingSpec<String> octopusAccountId) {
        exposedKey = checkNotNull(specifiedAnnotation).specify(ExposedKeyModule.super.getExposedKey().getTypeLiteral());
        this.zoneIdSpec = checkNotNull(zoneIdSpec);
        this.octopusApiKeySpec = checkNotNull(octopusApiKeySpec);
        this.octopusAccountId = checkNotNull(octopusAccountId);
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
        installLifecycleComponentModule(ExecutorProviderModule.builder()
                                                              .setThreadName(literally("Prices"))
                                                              .withAnnotation(forAnnotation(ExecutorProvider.class))
                                                              .build());
        installLifecycleComponentModule(OctopusEnergyModule.builder().build());
        octopusApiKeySpec.bind(String.class).annotatedWith(OctopusAgilePriceServiceImpl.ApiKey.class).installedBy(this::installLifecycleComponentModule);
        octopusAccountId.bind(String.class).annotatedWith(OctopusAgilePriceServiceImpl.AccountId.class).installedBy(this::installLifecycleComponentModule);
        bind(EnergyPriceService.class).annotatedWith(Octopus.class).to(registerLifecycleComponent(OctopusAgilePriceServiceImpl.class));

        installLifecycleComponentModule(new AgilePredictPriceModule());
        bind(EnergyPriceService.class).annotatedWith(AgilePredict.class).to(registerLifecycleComponent(AgilePredictEnergyPriceServiceImpl.class));

        bind(getExposedKey()).to(RealAndPredictedPriceService.class);
        expose(getExposedKey());
    }

    public static final class Builder implements TypedBuilder<ExposedKeyModule<EnergyPriceService>>, HasWithAnnotation {
        private SpecifiedAnnotation specifiedAnnotation = SpecifiedAnnotation.forNoAnnotation();
        private BindingSpec<ZoneId> zoneIdSpec = BindingSpec.boundTo(ZoneId.class);
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

        public Builder withZoneId(BindingSpec<ZoneId> zoneIdSpec) {
            this.zoneIdSpec = checkNotNull(zoneIdSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<EnergyPriceService> build() {
            return new OctopusAgilePriceServiceModule(specifiedAnnotation,
                                                 zoneIdSpec,
                                                 octopusApiKeySpec,
                                                 octopusAccountIdSpec);
        }
    }
}
