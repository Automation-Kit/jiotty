package net.yudichev.jiotty.persistence.db.psql;

import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.db.DbConnectionConfig;
import net.yudichev.jiotty.persistence.db.JdbcConnectionConfig;

import static com.google.common.base.Preconditions.checkNotNull;

public final class PsqlDataSourceFactoryModule extends BaseLifecycleComponentModule implements ExposedKeyModule<DataSourceFactory> {
    private final BindingSpec<JdbcConnectionConfig> connectionConfigSpec;

    private PsqlDataSourceFactoryModule(BindingSpec<JdbcConnectionConfig> connectionConfigSpec) {
        this.connectionConfigSpec = checkNotNull(connectionConfigSpec);
    }

    @Override
    protected void configure() {
        connectionConfigSpec.bind(JdbcConnectionConfig.class).installedBy(this::installLifecycleComponentModule);
        bind(getExposedKey()).to(PsqlDataSourceFactoryImpl.class);
        expose(getExposedKey());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends BaseModuleBuilder<DataSourceFactory, Builder> {
        private BindingSpec<JdbcConnectionConfig> connectionConfigSpec;

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

        @Override
        public ExposedKeyModule<DataSourceFactory> build() {
            return new PsqlDataSourceFactoryModule(connectionConfigSpec);
        }
    }
}
