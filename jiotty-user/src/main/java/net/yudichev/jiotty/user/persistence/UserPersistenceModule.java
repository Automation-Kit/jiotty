package net.yudichev.jiotty.user.persistence;

import com.google.inject.BindingAnnotation;
import com.google.inject.Module;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.lang.TypedBuilder;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainMigrator;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainModule;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.inject.BindingSpec.annotatedWith;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

public final class UserPersistenceModule extends BaseLifecycleComponentModule implements ExposedKeyModule<UserPersistence> {
    private static final String DEFAULT_DOMAIN_NAME = "users";

    private final BindingSpec<DataSourceFactory> dataSourceFactorySpec;
    private final BindingSpec<Integer> schemaVersionSpec;
    private final BindingSpec<String> domainNameSpec;
    private final BindingSpec<List<String>> initStatementsSpec;
    private final BindingSpec<PersistenceDomainMigrator> migratorSpec;

    private UserPersistenceModule(BindingSpec<DataSourceFactory> dataSourceFactorySpec,
                                  BindingSpec<Integer> schemaVersionSpec,
                                  BindingSpec<String> domainNameSpec,
                                  BindingSpec<List<String>> initStatementsSpec,
                                  BindingSpec<PersistenceDomainMigrator> migratorSpec) {
        this.dataSourceFactorySpec = checkNotNull(dataSourceFactorySpec, "dataSourceFactorySpec");
        this.schemaVersionSpec = checkNotNull(schemaVersionSpec, "schemaVersionSpec");
        this.domainNameSpec = checkNotNull(domainNameSpec, "domainNameSpec");
        this.initStatementsSpec = checkNotNull(initStatementsSpec, "initStatementsSpec");
        this.migratorSpec = checkNotNull(migratorSpec, "migratorSpec");
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
        initStatementsSpec.bind(new TypeLiteral<>() {})
                          .annotatedWith(InitStatements.class)
                          .installedBy(this::installLifecycleComponentModule);
        migratorSpec.bind(PersistenceDomainMigrator.class)
                    .annotatedWith(Migrator.class)
                    .installedBy(this::installLifecycleComponentModule);

        installLifecycleComponentModule(new ExecutorProviderModule("UserPersistence", Executor.class));
        installLifecycleComponentModule(PersistenceDomainModule.builder()
                                                               .setDataSourceFactory(dataSourceFactorySpec)
                                                               .setExecutor(annotatedWith(Executor.class))
                                                               .build());

        bind(getExposedKey()).to(registerLifecycleComponent(UserPersistenceImpl.class));
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
    @interface InitStatements {
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

    public static final class Builder implements TypedBuilder<Module> {
        private BindingSpec<DataSourceFactory> dataSourceFactorySpec;
        private BindingSpec<Integer> schemaVersionSpec;
        private BindingSpec<String> domainNameSpec = literally(DEFAULT_DOMAIN_NAME);
        private BindingSpec<List<String>> initStatementsSpec = literally(List.of());
        private BindingSpec<PersistenceDomainMigrator> migratorSpec = literally(PersistenceDomainMigrator.FAIL_ON_MIGRATION);

        public Builder setDataSourceFactory(BindingSpec<DataSourceFactory> dataSourceFactorySpec) {
            this.dataSourceFactorySpec = checkNotNull(dataSourceFactorySpec, "dataSourceFactorySpec");
            return this;
        }

        public Builder setSchemaVersion(BindingSpec<Integer> schemaVersionSpec) {
            this.schemaVersionSpec = checkNotNull(schemaVersionSpec, "schemaVersionSpec");
            return this;
        }

        public Builder withDomainName(BindingSpec<String> domainNameSpec) {
            this.domainNameSpec = checkNotNull(domainNameSpec, "domainNameSpec");
            return this;
        }

        public Builder withInitStatements(BindingSpec<List<String>> initStatementsSpec) {
            this.initStatementsSpec = checkNotNull(initStatementsSpec, "initStatementsSpec");
            return this;
        }

        public Builder withMigrator(BindingSpec<PersistenceDomainMigrator> migratorSpec) {
            this.migratorSpec = checkNotNull(migratorSpec, "migratorSpec");
            return this;
        }

        @Override
        public Module build() {
            return new UserPersistenceModule(dataSourceFactorySpec, schemaVersionSpec, domainNameSpec, initStatementsSpec, migratorSpec);
        }
    }
}
