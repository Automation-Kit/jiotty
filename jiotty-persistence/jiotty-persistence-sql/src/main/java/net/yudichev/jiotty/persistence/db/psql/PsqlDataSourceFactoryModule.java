package net.yudichev.jiotty.persistence.db.psql;

import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.db.JdbcConnectionConfig;

import static com.google.common.base.Preconditions.checkNotNull;

public final class PsqlDataSourceFactoryModule extends BaseLifecycleComponentModule implements ExposedKeyModule<DataSourceFactory> {
    private final BindingSpec<JdbcConnectionConfig> connectionConfigSpec;

    public PsqlDataSourceFactoryModule(String host, int port, String dbName, String username, BindingSpec<String> passwordSpec) {
        this(passwordSpec.map(TypeToken.of(String.class),
                              TypeToken.of(JdbcConnectionConfig.class),
                              password -> new JdbcConnectionConfig(
                                      "jdbc:postgresql://" + host + ":" + port + "/" + dbName + "?tcpKeepAlive=true", username, password)));
    }

    public PsqlDataSourceFactoryModule(BindingSpec<JdbcConnectionConfig> connectionConfigSpec) {
        this.connectionConfigSpec = checkNotNull(connectionConfigSpec);
    }

    @Override
    protected void configure() {
        connectionConfigSpec.bind(JdbcConnectionConfig.class).installedBy(this::installLifecycleComponentModule);
        bind(getExposedKey()).to(PsqlDataSourceFactoryImpl.class);
        expose(getExposedKey());
    }
}
