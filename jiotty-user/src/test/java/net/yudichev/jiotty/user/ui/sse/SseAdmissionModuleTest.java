package net.yudichev.jiotty.user.ui.sse;

import com.google.inject.Guice;
import com.google.inject.Injector;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SseAdmissionModuleTest {
    /// Every channel injected with the [SseAdmission] must share one instance, or the published limit gauge does not reflect the limit enforced.
    @Test
    void exposesOneSharedMeteredBudget() {
        var meterRegistry = new SimpleMeterRegistry();
        Injector injector = Guice.createInjector(binder -> binder.bind(MeterRegistry.class).toInstance(meterRegistry),
                                                 SseAdmissionModule.builder().build());

        SseAdmission admission = injector.getInstance(SseAdmission.class);

        assertThat(admission).isInstanceOf(SseAdmissionImpl.class).isSameAs(injector.getInstance(SseAdmission.class));
        assertThat(meterRegistry.find(SseAdmissionImpl.ACTIVE_CLIENTS_LIMIT_GAUGE).gauge()).isNotNull();
    }
}
