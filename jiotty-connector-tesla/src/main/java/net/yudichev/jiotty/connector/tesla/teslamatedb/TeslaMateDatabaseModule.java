package net.yudichev.jiotty.connector.tesla.teslamatedb;

import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;

import static com.google.common.base.Preconditions.checkNotNull;

public final class TeslaMateDatabaseModule extends BaseExposedKeyModule<TeslamateDatabase> {
    private final BindingSpec<DataSourceFactory> dataSourceFactorySpec;
    private final BindingSpec<String> vinSpec;

    private TeslaMateDatabaseModule(BindingSpec<DataSourceFactory> dataSourceFactorySpec,
                                    BindingSpec<String> vinSpec,
                                    SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.dataSourceFactorySpec = checkNotNull(dataSourceFactorySpec);
        this.vinSpec = checkNotNull(vinSpec);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        vinSpec.bind(String.class).annotatedWith(TeslamateDatabaseImpl.Vin.class).installedBy(this::installLifecycleComponentModule);
        dataSourceFactorySpec.bind(DataSourceFactory.class)
                             .annotatedWith(TeslamateDatabaseImpl.Dependency.class)
                             .installedBy(this::installLifecycleComponentModule);
        bind(exposedKey).to(registerLifecycleComponent(TeslamateDatabaseImpl.class));
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<TeslamateDatabase, Builder> {
        private BindingSpec<DataSourceFactory> dataSourceFactorySpec;
        private BindingSpec<String> vinSpec;

        public Builder setDataSourceFactory(BindingSpec<DataSourceFactory> dataSourceFactorySpec) {
            this.dataSourceFactorySpec = checkNotNull(dataSourceFactorySpec);
            return this;
        }

        public Builder setVin(BindingSpec<String> vinSpec) {
            this.vinSpec = checkNotNull(vinSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<TeslamateDatabase> build() {
            return new TeslaMateDatabaseModule(dataSourceFactorySpec, vinSpec, specifiedAnnotation());
        }
    }
}
