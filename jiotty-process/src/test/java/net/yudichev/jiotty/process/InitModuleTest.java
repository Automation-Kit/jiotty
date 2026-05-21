package net.yudichev.jiotty.process;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.keystore.KeyStoreAccess;
import net.yudichev.jiotty.logging.LoggingLevelConfigurator;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.db.DbConnectionConfig;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Paths;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static org.assertj.core.api.Assertions.assertThat;

class InitModuleTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void configure(boolean withVarStorePath) {
        InitModule.Builder builder = InitModule.builder()
                                               .setPathToKeystore(literally(Paths.get(",")))
                                               .setKeystorePass(literally("pass"))
                                               .setDbConnectionConfig(DbConnectionConfig.builder()
                                                                                        .setHost("H")
                                                                                        .setPort(13)
                                                                                        .setUsername("user")
                                                                                        .setPasswordSpec(literally("pass"))
                                                                                        .setDbName("dbNamt")
                                                                                        .build())
                                               .setAppModuleFactory(_ -> new AbstractModule() {});
        if (withVarStorePath) {
            builder.withVarStorePath(literally(Paths.get(",")));
        }
        Injector injector = Guice.createInjector(builder.build());
        assertThat(injector.findBindingsByType(new TypeLiteral<VarStore>() {})).isNotEmpty();
        assertThat(injector.findBindingsByType(new TypeLiteral<DataSourceFactory>() {})).isNotEmpty();
        assertThat(injector.findBindingsByType(new TypeLiteral<KeyStoreAccess>() {})).isNotEmpty();
        assertThat(injector.findBindingsByType(new TypeLiteral<LoggingLevelConfigurator>() {})).isNotEmpty();
    }
}