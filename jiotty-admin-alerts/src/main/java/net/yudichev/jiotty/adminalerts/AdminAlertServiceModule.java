package net.yudichev.jiotty.adminalerts;

import com.google.inject.BindingAnnotation;
import net.yudichev.jiotty.adminalerts.cleanup.AlertHistoryCleanupJob;
import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.lang.TypedBuilder;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainMigrator;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainModule;
import net.yudichev.jiotty.persistence.varstore.VarStore;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.inject.BindingSpec.annotatedWith;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;

public final class AdminAlertServiceModule extends BaseLifecycleComponentModule implements ExposedKeyModule<AdminAlertService> {
    private final BindingSpec<DataSourceFactory> dataSourceFactorySpec;
    private final BindingSpec<VarStore> varStoreSpec;
    private final BindingSpec<Integer> schemaVersionSpec;
    private final BindingSpec<String> domainNameSpec;
    private final BindingSpec<Duration> cleanupIntervalSpec;
    private final BindingSpec<Duration> cleanupRetentionSpec;

    private AdminAlertServiceModule(BindingSpec<DataSourceFactory> dataSourceFactorySpec,
                                    BindingSpec<VarStore> varStoreSpec,
                                    BindingSpec<Integer> schemaVersionSpec,
                                    BindingSpec<String> domainNameSpec,
                                    BindingSpec<Duration> cleanupIntervalSpec,
                                    BindingSpec<Duration> cleanupRetentionSpec) {
        this.dataSourceFactorySpec = checkNotNull(dataSourceFactorySpec, "dataSourceFactorySpec");
        this.varStoreSpec = checkNotNull(varStoreSpec, "varStoreSpec");
        this.schemaVersionSpec = checkNotNull(schemaVersionSpec, "schemaVersionSpec");
        this.domainNameSpec = checkNotNull(domainNameSpec, "domainNameSpec");
        this.cleanupIntervalSpec = checkNotNull(cleanupIntervalSpec, "cleanupIntervalSpec");
        this.cleanupRetentionSpec = checkNotNull(cleanupRetentionSpec, "cleanupRetentionSpec");
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        dataSourceFactorySpec.bind(DataSourceFactory.class)
                             .annotatedWith(Dependency.class)
                             .installedBy(this::installLifecycleComponentModule);
        schemaVersionSpec.bind(Integer.class)
                         .annotatedWith(SchemaVersion.class)
                         .installedBy(this::installLifecycleComponentModule);
        domainNameSpec.bind(String.class)
                      .annotatedWith(DomainName.class)
                      .installedBy(this::installLifecycleComponentModule);
        bind(PersistenceDomainMigrator.class)
                .annotatedWith(Migrator.class)
                .to(DomainMigratorImpl.class);
        varStoreSpec.bind(VarStore.class)
                    .annotatedWith(AlertHistoryCleanupJob.Dependency.class)
                    .installedBy(this::installLifecycleComponentModule);
        cleanupIntervalSpec.bind(Duration.class)
                           .annotatedWith(AlertHistoryCleanupJob.CleanupInterval.class)
                           .installedBy(this::installLifecycleComponentModule);
        cleanupRetentionSpec.bind(Duration.class)
                            .annotatedWith(AlertHistoryCleanupJob.HistoryRetention.class)
                            .installedBy(this::installLifecycleComponentModule);

        installLifecycleComponentModule(ExecutorProviderModule.builder()
                                                              .setThreadName(literally("AdminAlertService"))
                                                              .withAnnotation(forAnnotation(Executor.class))
                                                              .build());
        installLifecycleComponentModule(PersistenceDomainModule.builder()
                                                               .setDataSourceFactory(dataSourceFactorySpec)
                                                               .setExecutor(annotatedWith(Executor.class))
                                                               .build());

        bind(getExposedKey()).to(registerLifecycleComponent(AdminAlertServiceImpl.class));
        registerLifecycleComponent(AlertHistoryCleanupJob.class);
        expose(getExposedKey());
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface SchemaVersion {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface DomainName {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Migrator {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Executor {
    }

    public static final class Builder implements TypedBuilder<AdminAlertServiceModule> {
        private BindingSpec<DataSourceFactory> dataSourceFactorySpec;
        private BindingSpec<VarStore> varStoreSpec;
        private BindingSpec<Integer> schemaVersionSpec = literally(1);
        private BindingSpec<String> domainNameSpec = literally(AdminAlertSchema.DEFAULT_DOMAIN_NAME);
        private BindingSpec<Duration> cleanupIntervalSpec = literally(Duration.ofHours(24));
        private BindingSpec<Duration> cleanupRetentionSpec = literally(Duration.ofDays(180));

        public Builder setDataSourceFactory(BindingSpec<DataSourceFactory> dataSourceFactorySpec) {
            this.dataSourceFactorySpec = checkNotNull(dataSourceFactorySpec, "dataSourceFactorySpec");
            return this;
        }

        public Builder setVarStore(BindingSpec<VarStore> varStoreSpec) {
            this.varStoreSpec = checkNotNull(varStoreSpec, "varStoreSpec");
            return this;
        }

        public Builder withSchemaVersion(BindingSpec<Integer> schemaVersionSpec) {
            this.schemaVersionSpec = checkNotNull(schemaVersionSpec, "schemaVersionSpec");
            return this;
        }

        public Builder withDomainName(BindingSpec<String> domainNameSpec) {
            this.domainNameSpec = checkNotNull(domainNameSpec, "domainNameSpec");
            return this;
        }

        public Builder withCleanupInterval(BindingSpec<Duration> cleanupIntervalSpec) {
            this.cleanupIntervalSpec = checkNotNull(cleanupIntervalSpec, "cleanupIntervalSpec");
            return this;
        }

        public Builder withCleanupRetention(BindingSpec<Duration> cleanupRetentionSpec) {
            this.cleanupRetentionSpec = checkNotNull(cleanupRetentionSpec, "cleanupRetentionSpec");
            return this;
        }

        @Override
        public AdminAlertServiceModule build() {
            return new AdminAlertServiceModule(dataSourceFactorySpec,
                                               varStoreSpec,
                                               schemaVersionSpec,
                                               domainNameSpec,
                                               cleanupIntervalSpec,
                                               cleanupRetentionSpec);
        }
    }
}
