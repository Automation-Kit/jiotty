package net.yudichev.jiotty.connector.tesla.teslamatedb;

import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;

import static com.google.common.base.Preconditions.checkNotNull;

public final class TeslaMateDatabaseModule extends BaseLifecycleComponentModule implements ExposedKeyModule<TeslamateDatabase> {
    private final BindingSpec<DataSourceFactory> dataSourceFactorySpec;
    private final BindingSpec<String> vinSpec;

    public TeslaMateDatabaseModule(BindingSpec<DataSourceFactory> dataSourceFactorySpec,
                                   BindingSpec<String> vinSpec) {
        this.dataSourceFactorySpec = checkNotNull(dataSourceFactorySpec);
        this.vinSpec = checkNotNull(vinSpec);
    }

    @Override
    protected void configure() {
        vinSpec.bind(String.class).annotatedWith(TeslamateDatabaseImpl.Vin.class).installedBy(this::installLifecycleComponentModule);
        dataSourceFactorySpec.bind(DataSourceFactory.class)
                             .annotatedWith(TeslamateDatabaseImpl.Dependency.class)
                             .installedBy(this::installLifecycleComponentModule);
        bind(getExposedKey()).to(registerLifecycleComponent(TeslamateDatabaseImpl.class));
        expose(getExposedKey());
    }
}
