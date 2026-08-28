package net.yudichev.jiotty.process;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.app.ApplicationLifecycleControl;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.common.keystore.KeyStoreAccess;
import net.yudichev.jiotty.logging.LoggingLevelConfigurator;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.db.DbConnectionConfig;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Paths;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static org.assertj.core.api.Assertions.assertThat;

class InitModuleTest {
    private static final Module LIFECYCLE_CONTROL = new AbstractModule() {
        @Override
        protected void configure() {
            // Provided by Application at runtime; MetricsModule's lifecycle components need it bound.
            bind(ApplicationLifecycleControl.class).toInstance(ApplicationLifecycleControl.NOOP);
        }
    };

    /// A path backs the var store with a file; the database backs it otherwise. Both wire the same [VarStore] key.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void configure(boolean fileBackedVarStore) {
        InitModule.Builder builder = baseBuilder();
        if (fileBackedVarStore) {
            builder.withVarStorePath(literally(Paths.get(".")));
        }
        Injector injector = Guice.createInjector(builder.build());
        assertThat(injector.findBindingsByType(new TypeLiteral<VarStore>() {})).isNotEmpty();
        assertThat(injector.findBindingsByType(new TypeLiteral<DataSourceFactory>() {})).isNotEmpty();
        assertThat(injector.findBindingsByType(new TypeLiteral<KeyStoreAccess>() {})).isNotEmpty();
        assertThat(injector.findBindingsByType(new TypeLiteral<LoggingLevelConfigurator>() {})).isNotEmpty();
    }

    @Test
    void withMetricsInstallsMetricsModuleAtRoot() {
        int withoutMetrics = lifecycleComponentCount(baseBuilder());
        int withMetrics = lifecycleComponentCount(baseBuilder().withMetrics("test-app"));
        // Enabling metrics installs MetricsModule at the root injector, adding its lifecycle components there.
        assertThat(withMetrics).isGreaterThan(withoutMetrics);
    }

    private static int lifecycleComponentCount(InitModule.Builder builder) {
        return Guice.createInjector(builder.build(), LIFECYCLE_CONTROL).findBindingsByType(new TypeLiteral<LifecycleComponent>() {}).size();
    }

    private static InitModule.Builder baseBuilder() {
        return InitModule.builder()
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
    }
}
