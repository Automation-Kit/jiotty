package net.yudichev.jiotty.connector.brevo;

import com.google.inject.Guice;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.common.time.TimeModule;
import org.junit.jupiter.api.Test;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrevoClientModuleTest {
    @Test
    void configure() {
        var injector = Guice.createInjector(TimeModule.builder().build(),
                                            ExecutorModule.builder().build(),
                                            BrevoClientModule.builder().setApiKey(literally("test-key")).build());

        assertThat(injector.findBindingsByType(new TypeLiteral<LifecycleComponent>() {})).isNotEmpty();
        assertThat(injector.getInstance(BrevoClient.class)).isNotNull();
    }

    /// Rejected at build time rather than as a provisioning NPE, so a module wired without a key names the setter it is missing.
    @Test
    void apiKeyIsRequired() {
        assertThatThrownBy(() -> BrevoClientModule.builder().build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("setApiKey");
    }
}
