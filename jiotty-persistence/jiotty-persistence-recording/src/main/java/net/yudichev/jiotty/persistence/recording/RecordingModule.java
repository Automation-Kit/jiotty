package net.yudichev.jiotty.persistence.recording;

import com.google.inject.BindingAnnotation;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.persistence.psql.PsqlDataSourceFactory;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

public final class RecordingModule extends BaseLifecycleComponentModule implements ExposedKeyModule<RecordingService> {
    private final BindingSpec<PsqlDataSourceFactory> dataSourceFactorySpec;
    private final boolean readOnly;

    public RecordingModule(BindingSpec<PsqlDataSourceFactory> dataSourceFactorySpec, boolean readOnly) {
        this.dataSourceFactorySpec = checkNotNull(dataSourceFactorySpec);
        this.readOnly = readOnly;
    }

    @Override
    protected void configure() {
        dataSourceFactorySpec.bind(PsqlDataSourceFactory.class)
                             .annotatedWith(Dependency.class)
                             .installedBy(this::installLifecycleComponentModule);
        installLifecycleComponentModule(new ExecutorProviderModule("PSQL", PsqlExecutor.class));
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
