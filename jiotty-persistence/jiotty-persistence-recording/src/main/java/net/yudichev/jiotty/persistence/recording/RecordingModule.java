package net.yudichev.jiotty.persistence.recording;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.BindingAnnotation;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
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
import static net.yudichev.jiotty.common.inject.BindingSpec.boundTo;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;

public final class RecordingModule extends BaseExposedKeyModule<RecordingService> {
    /// Thread-name base of the single-threaded executor this module's persistence work runs on.
    @VisibleForTesting
    public static final String EXECUTOR_THREAD_NAME = "PSQL";

    private final BindingSpec<DataSourceFactory> dataSourceFactorySpec;
    private final boolean readOnly;

    private RecordingModule(BindingSpec<DataSourceFactory> dataSourceFactorySpec,
                            SpecifiedAnnotation specifiedAnnotation,
                            boolean readOnly) {
        super(specifiedAnnotation);
        this.dataSourceFactorySpec = checkNotNull(dataSourceFactorySpec);
        this.readOnly = readOnly;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        installLifecycleComponentModule(ExecutorProviderModule.builder()
                                                              .setThreadName(literally(EXECUTOR_THREAD_NAME))
                                                              .withAnnotation(forAnnotation(PsqlExecutor.class))
                                                              .build());
        installLifecycleComponentModule(PersistenceDomainModule.builder()
                                                               .setDataSourceFactory(dataSourceFactorySpec)
                                                               .setExecutor(annotatedWith(PsqlExecutor.class))
                                                               .build());
        dataSourceFactorySpec.bind(DataSourceFactory.class)
                             .annotatedWith(Dependency.class)
                             .installedBy(this::installLifecycleComponentModule);
        install(new FactoryModuleBuilder()
                        .implement(PostgresqlDestination.class, readOnly ? ReadOnlyPostgresqlDestination.class : PostgresqlDestinationImpl.class)
                        .build(PostgresqlDestinationFactory.class));

        install(new FactoryModuleBuilder()
                        .implement(UIDestination.class, UIDestinationImpl.class)
                        .build(UIDestinationFactory.class));

        bind(DestinationFactory.class).to(DestinationFactoryImpl.class);
        bind(exposedKey).to(registerLifecycleComponent(RecordingServiceImpl.class));
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<RecordingService, Builder> {
        private BindingSpec<DataSourceFactory> dataSourceFactorySpec = boundTo(DataSourceFactory.class);
        private boolean readOnly;

        public Builder withDataSourceFactory(BindingSpec<DataSourceFactory> dataSourceFactorySpec) {
            this.dataSourceFactorySpec = checkNotNull(dataSourceFactorySpec);
            return this;
        }

        public Builder withReadOnly(boolean readOnly) {
            this.readOnly = readOnly;
            return this;
        }

        @Override
        public ExposedKeyModule<RecordingService> build() {
            return new RecordingModule(dataSourceFactorySpec, specifiedAnnotation(), readOnly);
        }
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
