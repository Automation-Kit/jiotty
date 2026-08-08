package net.yudichev.jiotty.connector.anthropic;

import com.google.inject.Guice;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.common.time.TimeModule;
import org.junit.jupiter.api.Test;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static org.assertj.core.api.Assertions.assertThat;

class AnthropicClientModuleTest {
    @Test
    void configure() {
        var injector = Guice.createInjector(TimeModule.builder().build(),
                                            ExecutorModule.builder().build(),
                                            AnthropicClientModule.builder().setApiKey(literally("test-key")).build());

        assertThat(injector.findBindingsByType(new TypeLiteral<LifecycleComponent>() {})).isNotEmpty();
        assertThat(injector.getInstance(AnthropicClient.class)).isNotNull();
    }
}
