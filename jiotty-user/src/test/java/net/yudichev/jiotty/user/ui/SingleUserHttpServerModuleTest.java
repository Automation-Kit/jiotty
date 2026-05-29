package net.yudichev.jiotty.user.ui;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.time.TimeModule;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.varstore.VarStoreModule;
import org.junit.jupiter.api.Test;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static org.mockito.Mockito.mock;

class SingleUserHttpServerModuleTest {
    @Test
    void configure() {
        Guice.createInjector(new ExecutorModule(),
                             new TimeModule(),
                             new AbstractModule() {
                                 @Override
                                 protected void configure() {
                                     bind(MeterRegistry.class).toInstance(new SimpleMeterRegistry());
                                 }
                             },
                             UIServerModule.builder().build(),
                             VarStoreModule.builder()
                                           .withDataSourceFactory(literally(mock(DataSourceFactory.class)))
                                           .build(),
                             SingleUserHttpServerModule.builder().setListenPort(literally(4568)).build());
    }
}
