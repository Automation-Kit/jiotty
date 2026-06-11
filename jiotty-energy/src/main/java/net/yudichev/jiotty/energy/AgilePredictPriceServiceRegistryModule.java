package net.yudichev.jiotty.energy;

import com.google.inject.Key;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.connector.octopusenergy.agilepredict.AgilePredictPriceModule;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.boundTo;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static net.yudichev.jiotty.energy.Bindings.AgilePredict;
import static net.yudichev.jiotty.energy.Bindings.Dependency;

/// App-scope module that installs the [AgilePredictPriceServiceRegistry] together with the AgilePredict connector, its own executor, and the
/// [AgilePredictEnergyPriceServiceImpl.Factory] assisted-inject factory used by the registry to construct per-region impls. The
/// [PriceRetrievalStatusHandler] every per-region impl reports to is supplied via the builder, defaulting to [NoOpPriceRetrievalStatusHandler].
public final class AgilePredictPriceServiceRegistryModule extends BaseLifecycleComponentModule
        implements ExposedKeyModule<AgilePredictPriceServiceRegistry> {
    private final BindingSpec<PriceRetrievalStatusHandler> statusHandlerSpec;
    private final Key<AgilePredictPriceServiceRegistry> exposedKey;

    private AgilePredictPriceServiceRegistryModule(SpecifiedAnnotation specifiedAnnotation,
                                                   BindingSpec<PriceRetrievalStatusHandler> statusHandlerSpec) {
        exposedKey = specifiedAnnotation.specify(ExposedKeyModule.super.getExposedKey().getTypeLiteral());
        this.statusHandlerSpec = checkNotNull(statusHandlerSpec);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Key<AgilePredictPriceServiceRegistry> getExposedKey() {
        return exposedKey;
    }

    @Override
    protected void configure() {
        installLifecycleComponentModule(ExecutorProviderModule.builder()
                                                              .setThreadName(literally("AgilePredictPrices"))
                                                              .withAnnotation(forAnnotation(AgilePredict.class))
                                                              .build());
        installLifecycleComponentModule(new AgilePredictPriceModule());
        statusHandlerSpec.bind(PriceRetrievalStatusHandler.class)
                         .annotatedWith(Dependency.class)
                         .installedBy(this::installLifecycleComponentModule);
        install(new FactoryModuleBuilder()
                        .implement(AgilePredictEnergyPriceService.class, AgilePredictEnergyPriceServiceImpl.class)
                        .build(AgilePredictEnergyPriceServiceImpl.Factory.class));
        bind(exposedKey).to(registerLifecycleComponent(AgilePredictPriceServiceRegistryImpl.class));
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<AgilePredictPriceServiceRegistry, Builder> {
        private BindingSpec<PriceRetrievalStatusHandler> statusHandlerSpec = boundTo(NoOpPriceRetrievalStatusHandler.class);

        public Builder withStatusHandler(BindingSpec<PriceRetrievalStatusHandler> statusHandlerSpec) {
            this.statusHandlerSpec = checkNotNull(statusHandlerSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<AgilePredictPriceServiceRegistry> build() {
            return new AgilePredictPriceServiceRegistryModule(specifiedAnnotation(), statusHandlerSpec);
        }
    }
}
