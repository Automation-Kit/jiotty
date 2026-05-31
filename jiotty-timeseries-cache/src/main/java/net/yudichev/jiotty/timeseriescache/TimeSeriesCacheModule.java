package net.yudichev.jiotty.timeseriescache;

import com.google.common.collect.ImmutableList;
import com.google.inject.BindingAnnotation;
import com.google.inject.Key;
import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainMigrator;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainModule;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import net.yudichev.jiotty.timeseriescache.cleanup.ActiveUserIdsSupplier;
import net.yudichev.jiotty.timeseriescache.cleanup.TimeSeriesCacheCleanupJob;
import org.jspecify.annotations.Nullable;

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

public final class TimeSeriesCacheModule extends BaseLifecycleComponentModule implements ExposedKeyModule<TimeSeriesCache> {
    private final BindingSpec<DataSourceFactory> dataSourceFactorySpec;
    private final BindingSpec<Integer> schemaVersionSpec;
    private final BindingSpec<String> domainNameSpec;
    private final @Nullable BindingSpec<VarStore> cleanupVarStoreSpec;
    private final @Nullable BindingSpec<ActiveUserIdsSupplier> cleanupActiveUserIdsSupplierSpec;
    private final BindingSpec<Duration> cleanupIntervalSpec;
    private final Key<TimeSeriesCache> exposedKey;

    private TimeSeriesCacheModule(SpecifiedAnnotation specifiedAnnotation,
                                  BindingSpec<DataSourceFactory> dataSourceFactorySpec,
                                  BindingSpec<Integer> schemaVersionSpec,
                                  BindingSpec<String> domainNameSpec,
                                  @Nullable BindingSpec<VarStore> cleanupVarStoreSpec,
                                  @Nullable BindingSpec<ActiveUserIdsSupplier> cleanupActiveUserIdsSupplierSpec,
                                  BindingSpec<Duration> cleanupIntervalSpec) {
        exposedKey = specifiedAnnotation.specify(ExposedKeyModule.super.getExposedKey().getTypeLiteral());
        this.dataSourceFactorySpec = checkNotNull(dataSourceFactorySpec, "dataSourceFactorySpec");
        this.schemaVersionSpec = checkNotNull(schemaVersionSpec, "schemaVersionSpec");
        this.domainNameSpec = checkNotNull(domainNameSpec, "domainNameSpec");
        this.cleanupVarStoreSpec = cleanupVarStoreSpec;
        this.cleanupActiveUserIdsSupplierSpec = cleanupActiveUserIdsSupplierSpec;
        this.cleanupIntervalSpec = checkNotNull(cleanupIntervalSpec, "cleanupIntervalSpec");
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Key<TimeSeriesCache> getExposedKey() {
        return exposedKey;
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

        // Production write codec defaults to Smile; both codecs sit in the read set so rows written in either format remain readable forever.
        var smileCodec = new SmileCodec();
        var jsonCodec = new JsonUtf8Codec();
        bind(CodecRegistry.class).toInstance(new CodecRegistry(smileCodec, ImmutableList.of(smileCodec, jsonCodec)));

        installLifecycleComponentModule(ExecutorProviderModule.builder()
                                                              .setThreadName(literally("TimeSeriesCache"))
                                                              .withAnnotation(forAnnotation(Executor.class))
                                                              .build());
        installLifecycleComponentModule(PersistenceDomainModule.builder()
                                                               .setDataSourceFactory(dataSourceFactorySpec)
                                                               .setExecutor(annotatedWith(Executor.class))
                                                               .build());

        bind(exposedKey).to(registerLifecycleComponent(TimeSeriesCacheImpl.class));
        if (cleanupVarStoreSpec != null && cleanupActiveUserIdsSupplierSpec != null) {
            cleanupVarStoreSpec.bind(VarStore.class)
                               .annotatedWith(TimeSeriesCacheCleanupJob.Dependency.class)
                               .installedBy(this::installLifecycleComponentModule);
            cleanupActiveUserIdsSupplierSpec.bind(ActiveUserIdsSupplier.class)
                                            .annotatedWith(CleanupSupplier.class)
                                            .installedBy(this::installLifecycleComponentModule);
            bind(ActiveUserIdsSupplier.class).to(Key.get(ActiveUserIdsSupplier.class, CleanupSupplier.class));
            cleanupIntervalSpec.bind(Duration.class)
                               .annotatedWith(TimeSeriesCacheCleanupJob.CleanupInterval.class)
                               .installedBy(this::installLifecycleComponentModule);
            registerLifecycleComponent(TimeSeriesCacheCleanupJob.class);
        }
        expose(exposedKey);
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

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface CleanupSupplier {
    }

    public static final class Builder extends BaseModuleBuilder<TimeSeriesCache, Builder> {
        private BindingSpec<DataSourceFactory> dataSourceFactorySpec;
        private BindingSpec<Integer> schemaVersionSpec = literally(1);
        private BindingSpec<String> domainNameSpec = literally(TimeSeriesCacheSchema.DEFAULT_DOMAIN_NAME);
        private @Nullable BindingSpec<VarStore> cleanupVarStoreSpec;
        private @Nullable BindingSpec<ActiveUserIdsSupplier> cleanupActiveUserIdsSupplierSpec;
        private BindingSpec<Duration> cleanupIntervalSpec = literally(Duration.ofHours(24));

        public Builder setDataSourceFactory(BindingSpec<DataSourceFactory> dataSourceFactorySpec) {
            this.dataSourceFactorySpec = checkNotNull(dataSourceFactorySpec, "dataSourceFactorySpec");
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

        /// Enables the periodic cleanup job. Both the `VarStore` (for next-run-timestamp persistence) and the [ActiveUserIdsSupplier] must be supplied;
        /// omitting either disables the job entirely.
        public Builder withCleanup(BindingSpec<VarStore> cleanupVarStoreSpec, BindingSpec<ActiveUserIdsSupplier> cleanupActiveUserIdsSupplierSpec) {
            this.cleanupVarStoreSpec = checkNotNull(cleanupVarStoreSpec, "cleanupVarStoreSpec");
            this.cleanupActiveUserIdsSupplierSpec = checkNotNull(cleanupActiveUserIdsSupplierSpec, "cleanupActiveUserIdsSupplierSpec");
            return this;
        }

        public Builder withCleanupInterval(BindingSpec<Duration> cleanupIntervalSpec) {
            this.cleanupIntervalSpec = checkNotNull(cleanupIntervalSpec, "cleanupIntervalSpec");
            return this;
        }

        @Override
        public ExposedKeyModule<TimeSeriesCache> build() {
            return new TimeSeriesCacheModule(specifiedAnnotation(),
                                             dataSourceFactorySpec,
                                             schemaVersionSpec,
                                             domainNameSpec,
                                             cleanupVarStoreSpec,
                                             cleanupActiveUserIdsSupplierSpec,
                                             cleanupIntervalSpec);
        }
    }
}
