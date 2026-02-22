package net.yudichev.jiotty.persistence.recording;

import com.google.inject.BindingAnnotation;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainModule;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.inject.BindingSpec.annotatedWith;

public final class RecordingModule extends BaseLifecycleComponentModule implements ExposedKeyModule<RecordingService> {
    private final BindingSpec<DataSourceFactory> dataSourceFactorySpec;
    private final boolean readOnly;

    public RecordingModule(BindingSpec<DataSourceFactory> dataSourceFactorySpec, boolean readOnly) {
        this.dataSourceFactorySpec = checkNotNull(dataSourceFactorySpec);
        this.readOnly = readOnly;
    }

    @Override
    protected void configure() {
        dataSourceFactorySpec.bind(DataSourceFactory.class)
                             .annotatedWith(Dependency.class)
                             .installedBy(this::installLifecycleComponentModule);
        installLifecycleComponentModule(new ExecutorProviderModule("PSQL", PsqlExecutor.class));
        installLifecycleComponentModule(PersistenceDomainModule.builder()
                                                               .setDataSourceFactory(dataSourceFactorySpec)
                                                               .setExecutor(annotatedWith(PsqlExecutor.class))
                                                               .build());
        install(new FactoryModuleBuilder()
                        .implement(PostgresqlDestination.class, readOnly ? ReadOnlyPostgresqlDestination.class : PostgresqlDestinationImpl.class)
                        .build(PostgresqlDestinationFactory.class));

        install(new FactoryModuleBuilder()
                        .implement(UIDestination.class, UIDestinationImpl.class)
                        .build(UIDestinationFactory.class));

        bind(DestinationFactory.class).to(DestinationFactoryImpl.class);
        bind(getExposedKey()).to(registerLifecycleComponent(RecordingServiceImpl.class));
        expose(getExposedKey());
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface PsqlExecutor {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }
}
