package net.yudichev.jiotty.process;

import com.google.inject.AbstractModule;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.TypeLiteral;
import io.micrometer.core.instrument.MeterRegistry;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.keystore.KeyStoreAccessModule;
import net.yudichev.jiotty.common.lang.TypedBuilder;
import net.yudichev.jiotty.common.metrics.MetricsModule;
import net.yudichev.jiotty.common.time.TimeModule;
import net.yudichev.jiotty.logging.PersistingLog4jLevelConfiguratorModule;
import net.yudichev.jiotty.persistence.db.DbConnectionConfig;
import net.yudichev.jiotty.persistence.db.psql.PsqlDataSourceFactoryModule;
import net.yudichev.jiotty.persistence.varstore.VarStoreModule;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.Boolean.FALSE;
import static net.yudichev.jiotty.common.inject.BindingSpec.boundTo;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;

public final class InitModule extends AbstractModule {
    private final DbConnectionConfig dbConnectionConfig;
    private final @Nullable BindingSpec<Path> varStorePathSpec;
    private final BindingSpec<Path> pathToKeystoreSpec;
    private final BindingSpec<String> keystorePassSpec;
    private final BindingSpec<Boolean> singeUserSpec;
    private final Function<Injector, Module> appModuleFactory;
    private final @Nullable SpecifiedAnnotation varStoreAnnotation;
    private final BindingSpec<String> varStoreTableNameSpec;
    private final @Nullable BindingSpec<String> varStoreEncryptionKeyAliasSpec;
    private final @Nullable String applicationName;

    private InitModule(DbConnectionConfig dbConnectionConfig,
                       @Nullable BindingSpec<Path> varStorePathSpec,
                       BindingSpec<Path> pathToKeystoreSpec,
                       BindingSpec<String> keystorePassSpec,
                       BindingSpec<Boolean> singeUserSpec,
                       Function<Injector, Module> appModuleFactory,
                       @Nullable SpecifiedAnnotation varStoreAnnotation,
                       BindingSpec<String> varStoreTableNameSpec,
                       @Nullable BindingSpec<String> varStoreEncryptionKeyAliasSpec,
                       @Nullable String applicationName) {
        this.dbConnectionConfig = checkNotNull(dbConnectionConfig);
        this.varStorePathSpec = varStorePathSpec;
        this.pathToKeystoreSpec = checkNotNull(pathToKeystoreSpec);
        this.keystorePassSpec = checkNotNull(keystorePassSpec);
        this.singeUserSpec = checkNotNull(singeUserSpec);
        this.appModuleFactory = checkNotNull(appModuleFactory);
        this.varStoreAnnotation = varStoreAnnotation;
        this.varStoreTableNameSpec = checkNotNull(varStoreTableNameSpec);
        this.varStoreEncryptionKeyAliasSpec = varStoreEncryptionKeyAliasSpec;
        this.applicationName = applicationName;
    }

    @Override
    protected void configure() {
        install(new ExecutorModule());
        if (applicationName != null) {
            install(MetricsModule.builder()
                                 .withCommonTag("application", applicationName)
                                 .build());
        }
        install(new TimeModule());
        var keyStoreAccessModule = KeyStoreAccessModule.builder()
                                                       .setPathToKeystore(pathToKeystoreSpec)
                                                       .setKeystorePass(keystorePassSpec)
                                                       .build();
        install(keyStoreAccessModule);
        var dataSourceFactoryBuilder = PsqlDataSourceFactoryModule.builder()
                                                                  .setConnectionConfig(dbConnectionConfig);
        if (applicationName != null) {
            // Metrics are enabled (MetricsModule installed above), so let the default pools surface their hikaricp_* gauges.
            dataSourceFactoryBuilder.withMeterRegistry(boundTo(MeterRegistry.class));
        }
        var dataSourceFactoryModule = dataSourceFactoryBuilder.build();
        install(dataSourceFactoryModule);
        var varStoreModuleBuilder = VarStoreModule.builder();
        if (varStoreAnnotation != null) {
            varStoreModuleBuilder.withAnnotation(varStoreAnnotation);
        }
        if (varStorePathSpec != null) {
            varStoreModuleBuilder.withPath(varStorePathSpec);
        }
        if (varStoreEncryptionKeyAliasSpec != null) {
            varStoreModuleBuilder
                    .withEncryptionKeyAlias(varStoreEncryptionKeyAliasSpec)
                    .withKeyStoreAccess(boundTo(keyStoreAccessModule.getExposedKey()));
        }
        var varStoreModule = varStoreModuleBuilder
                .withDataSourceFactory(boundTo(dataSourceFactoryModule.getExposedKey()))
                .withSingleUser(singeUserSpec)
                .withTableName(varStoreTableNameSpec)
                .build();
        install(varStoreModule);
        install(PersistingLog4jLevelConfiguratorModule.builder()
                                                      .setVarStore(boundTo(varStoreModule.getExposedKey()))
                                                      .build());
        bind(new TypeLiteral<Function<Injector, Module>>() {}).annotatedWith(AppManager.Dependency.class).toInstance(appModuleFactory);
        bind(LifecycleComponent.class).annotatedWith(uniqueAnnotation()).to(AppManager.class);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder implements TypedBuilder<InitModule> {
        private DbConnectionConfig dbConnectionConfig;
        private BindingSpec<Path> varStorePathSpec;
        private BindingSpec<Path> pathToKeystoreSpec;
        private BindingSpec<String> keystorePassSpec;
        private BindingSpec<Boolean> singleUserSpec = literally(FALSE);
        private Function<Injector, Module> appModuleFactory;
        private BindingSpec<String> varStoreTableNameSpec = literally("var_store");
        private SpecifiedAnnotation varStoreAnnotation;
        private BindingSpec<String> varStoreEncryptionKeyAliasSpec;
        private String applicationName;

        public Builder setDbConnectionConfig(DbConnectionConfig dbConnectionConfig) {
            this.dbConnectionConfig = checkNotNull(dbConnectionConfig);
            return this;
        }

        public Builder setPathToKeystore(BindingSpec<Path> pathToKeystoreSpec) {
            this.pathToKeystoreSpec = checkNotNull(pathToKeystoreSpec);
            return this;
        }

        public Builder setKeystorePass(BindingSpec<String> keystorePassSpec) {
            this.keystorePassSpec = checkNotNull(keystorePassSpec);
            return this;
        }

        public Builder setAppModuleFactory(Function<Injector, Module> appModuleFactory) {
            this.appModuleFactory = checkNotNull(appModuleFactory);
            return this;
        }

        public Builder withSingleUser(BindingSpec<Boolean> singleUserSpec) {
            this.singleUserSpec = checkNotNull(singleUserSpec);
            return this;
        }

        public Builder withVarStorePath(BindingSpec<Path> varStorePathSpec) {
            this.varStorePathSpec = checkNotNull(varStorePathSpec);
            return this;
        }

        public Builder withVarStoreTableName(BindingSpec<String> varStoreTableNameSpec) {
            this.varStoreTableNameSpec = checkNotNull(varStoreTableNameSpec);
            return this;
        }

        public Builder withVarStoreAnnotation(SpecifiedAnnotation varStoreAnnotation) {
            this.varStoreAnnotation = checkNotNull(varStoreAnnotation);
            return this;
        }

        public Builder withVarStoreEncryptionKeyAlias(BindingSpec<String> varStoreEncryptionKeyAliasSpec) {
            this.varStoreEncryptionKeyAliasSpec = checkNotNull(varStoreEncryptionKeyAliasSpec);
            return this;
        }

        /// Triggers installation of [MetricsModule] at the **root** injector.
        ///
        /// @param applicationName the `application` metric common tag.
        public Builder withMetrics(String applicationName) {
            this.applicationName = checkNotNull(applicationName);
            return this;
        }

        @Override
        public InitModule build() {
            return new InitModule(dbConnectionConfig,
                                  varStorePathSpec,
                                  pathToKeystoreSpec,
                                  keystorePassSpec,
                                  singleUserSpec,
                                  appModuleFactory,
                                  varStoreAnnotation,
                                  varStoreTableNameSpec,
                                  varStoreEncryptionKeyAliasSpec,
                                  applicationName);
        }
    }
}
