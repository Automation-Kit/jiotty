package net.yudichev.jiotty.persistence.db.psql;

import com.google.common.reflect.TypeToken;
import com.google.inject.BindingAnnotation;
import com.google.inject.Key;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.db.DbConnectionConfig;
import net.yudichev.jiotty.persistence.db.JdbcConnectionConfig;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

public final class PsqlDataSourceFactoryModule extends BaseLifecycleComponentModule implements ExposedKeyModule<DataSourceFactory> {
    /// The default pool size, used by callers that do not set one explicitly. A consumer that carries all users' load on a single shared pool (e.g. the
    /// recording service) overrides this via [Builder#withMaximumPoolSize].
    private static final int DEFAULT_MAXIMUM_POOL_SIZE = 2;

    private final BindingSpec<JdbcConnectionConfig> connectionConfigSpec;
    private final BindingSpec<Integer> maximumPoolSizeSpec;
    private final Key<DataSourceFactory> exposedKey;

    private PsqlDataSourceFactoryModule(BindingSpec<JdbcConnectionConfig> connectionConfigSpec,
                                        BindingSpec<Integer> maximumPoolSizeSpec,
                                        SpecifiedAnnotation specifiedAnnotation) {
        this.connectionConfigSpec = checkNotNull(connectionConfigSpec);
        this.maximumPoolSizeSpec = checkNotNull(maximumPoolSizeSpec);
        // Honour the caller's annotation so several independently-sized pools can coexist (e.g. a large recording pool alongside the default one); with no
        //  annotation this is the plain DataSourceFactory, as before.
        exposedKey = specifiedAnnotation.specify(ExposedKeyModule.super.getExposedKey().getTypeLiteral());
    }

    @Override
    public Key<DataSourceFactory> getExposedKey() {
        return exposedKey;
    }

    @Override
    protected void configure() {
        connectionConfigSpec.bind(JdbcConnectionConfig.class).installedBy(this::installLifecycleComponentModule);
        maximumPoolSizeSpec.bind(Integer.class).annotatedWith(MaximumPoolSize.class).installedBy(this::installLifecycleComponentModule);
        bind(getExposedKey()).to(PsqlDataSourceFactoryImpl.class);
        expose(getExposedKey());
    }

    public static Builder builder() {
        return new Builder();
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface MaximumPoolSize {
    }

    public static final class Builder extends BaseModuleBuilder<DataSourceFactory, Builder> {
        private BindingSpec<JdbcConnectionConfig> connectionConfigSpec;
        private BindingSpec<Integer> maximumPoolSizeSpec = literally(DEFAULT_MAXIMUM_POOL_SIZE);

        public Builder setConnectionConfig(BindingSpec<JdbcConnectionConfig> connectionConfigSpec) {
            this.connectionConfigSpec = checkNotNull(connectionConfigSpec);
            return this;
        }

        public Builder setConnectionConfig(DbConnectionConfig dbConnectionConfig) {
            connectionConfigSpec =
                    dbConnectionConfig.passwordSpec().map(
                            new TypeToken<>() {},
                            new TypeToken<>() {},
                            password -> new JdbcConnectionConfig(
                                    "jdbc:postgresql://" + dbConnectionConfig.host() + ":" + dbConnectionConfig.port() + "/" + dbConnectionConfig.dbName()
                                    + "?tcpKeepAlive=true", dbConnectionConfig.username(), password));
            return this;
        }

        /// Sets the Hikari pool's fixed size — both the maximum and the minimum-idle floor (see [PsqlDataSourceFactoryImpl]). Defaults to
        /// [#DEFAULT_MAXIMUM_POOL_SIZE].
        public Builder withMaximumPoolSize(BindingSpec<Integer> maximumPoolSizeSpec) {
            this.maximumPoolSizeSpec = checkNotNull(maximumPoolSizeSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<DataSourceFactory> build() {
            return new PsqlDataSourceFactoryModule(connectionConfigSpec, maximumPoolSizeSpec, specifiedAnnotation());
        }
    }
}
