package net.yudichev.jiotty.world.homelocation;

import com.google.inject.Guice;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.user.ui.UIServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class HomeLocationModuleTest {
    @Test
    void configure(@Mock UIServer uiServer) {
        var injector = Guice.createInjector(ExecutorModule.builder().build(),
                                            binder -> binder.bind(UIServer.class).toInstance(uiServer),
                                            HomeLocationModule.builder().build());

        assertThat(injector.findBindingsByType(new TypeLiteral<LifecycleComponent>() {})).isNotEmpty();
    }
}
