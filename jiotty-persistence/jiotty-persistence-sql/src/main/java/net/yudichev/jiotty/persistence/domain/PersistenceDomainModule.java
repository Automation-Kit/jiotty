package net.yudichev.jiotty.persistence.domain;

import com.google.inject.BindingAnnotation;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

public final class PersistenceDomainModule extends BaseExposedKeyModule<PersistenceDomainService> {
    private final BindingSpec<DataSourceFactory> dataSourceFactorySpec;
    private final BindingSpec<SchedulingExecutor> executorSpec;

    private PersistenceDomainModule(BindingSpec<DataSourceFactory> dataSourceFactorySpec,
                                    BindingSpec<SchedulingExecutor> executorSpec,
                                    SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.dataSourceFactorySpec = checkNotNull(dataSourceFactorySpec);
        this.executorSpec = checkNotNull(executorSpec);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        dataSourceFactorySpec.bind(DataSourceFactory.class)
                             .annotatedWith(Dependency.class)
                             .installedBy(this::installLifecycleComponentModule);
        executorSpec.bind(SchedulingExecutor.class)
                    .annotatedWith(Dependency.class)
                    .installedBy(this::installLifecycleComponentModule);
        bind(exposedKey).to(registerLifecycleComponent(PersistenceDomainServiceImpl.class));
        expose(exposedKey);
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }

    public static final class Builder extends BaseModuleBuilder<PersistenceDomainService, Builder> {
        private BindingSpec<DataSourceFactory> dataSourceFactorySpec;
        private BindingSpec<SchedulingExecutor> executorSpec;

        public Builder setDataSourceFactory(BindingSpec<DataSourceFactory> dataSourceFactorySpec) {
            this.dataSourceFactorySpec = checkNotNull(dataSourceFactorySpec);
            return this;
        }

        public Builder setExecutor(BindingSpec<SchedulingExecutor> executorSpec) {
            this.executorSpec = checkNotNull(executorSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<PersistenceDomainService> build() {
            return new PersistenceDomainModule(dataSourceFactorySpec, executorSpec, specifiedAnnotation());
        }
    }
}
