package net.yudichev.jiotty.user.ui;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import io.micrometer.core.instrument.MeterRegistry;
import net.yudichev.jiotty.adminalerts.LoggingAdminAlertServiceModule;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.metrics.NoopMeterRegistry;
import net.yudichev.jiotty.common.time.TimeModule;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.varstore.VarStoreModule;
import org.junit.jupiter.api.Test;

import static net.yudichev.jiotty.common.inject.BindingSpec.exposedBy;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SingleUserHttpServerModuleTest {
    @Test
    void exposesItsServerUnderTheBareKey() {
        ExposedKeyModule<UIHttpServer> module = SingleUserHttpServerModule.builder().setListenPort(literally(4568)).build();

        assertThat(module.getExposedKey()).isEqualTo(Key.get(UIHttpServer.class));
        assertThat(injectorFor(module).getExistingBinding(module.getExposedKey())).isNotNull();
    }

    /// The exposed key has to follow the annotation the caller asks for, otherwise a second server in the same injector would collide with the first.
    @Test
    void exposesItsServerUnderTheRequestedAnnotation() {
        ExposedKeyModule<UIHttpServer> module = SingleUserHttpServerModule.builder()
                                                                          .setListenPort(literally(4568))
                                                                          .withAnnotation(forAnnotation(uniqueAnnotation()))
                                                                          .build();

        assertThat(module.getExposedKey()).isNotEqualTo(Key.get(UIHttpServer.class));
        assertThat(injectorFor(module).getExistingBinding(module.getExposedKey())).isNotNull();
    }

    private static Injector injectorFor(ExposedKeyModule<UIHttpServer> module) {
        return Guice.createInjector(ExecutorModule.builder().build(),
                                    TimeModule.builder().build(),
                                    new AbstractModule() {
                                        @Override
                                        protected void configure() {
                                            bind(MeterRegistry.class).toInstance(new NoopMeterRegistry());
                                        }
                                    },
                                    UIServerModule.builder()
                                                  .setAdminAlertService(exposedBy(LoggingAdminAlertServiceModule.builder().build()))
                                                  .build(),
                                    VarStoreModule.builder()
                                                  .withDataSourceFactory(literally(mock(DataSourceFactory.class)))
                                                  .build(),
                                    module);
    }
}
