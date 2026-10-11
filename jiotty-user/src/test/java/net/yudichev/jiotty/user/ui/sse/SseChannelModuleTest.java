package net.yudichev.jiotty.user.ui.sse;

import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.reflect.TypeToken;
import com.google.inject.Guice;
import com.google.inject.Injector;
import net.yudichev.jiotty.adminalerts.TestAdminAlertService;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.common.time.TimeModule;
import org.junit.jupiter.api.Test;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static org.assertj.core.api.Assertions.assertThat;

class SseChannelModuleTest {
    private static final ObjectWriter WRITER = Json.createWriterFor(new TypeToken<>() {});

    /// The factory is the module's only export, so building a channel from it proves the alert service and the assisted wiring both resolved.
    @Test
    void exposesAFactoryThatBuildsChannels() {
        Injector injector = Guice.createInjector(TimeModule.builder().build(),
                                                 SseChannelModule.builder().setAlertService(literally(new TestAdminAlertService())).build());

        SseChannel channel = injector.getInstance(SseChannel.Factory.class)
                                     .create("test", new ProgrammableClock().createSingleThreadedSchedulingExecutor("test"), WRITER, 1);

        assertThat(channel).isNotNull();
    }
}
