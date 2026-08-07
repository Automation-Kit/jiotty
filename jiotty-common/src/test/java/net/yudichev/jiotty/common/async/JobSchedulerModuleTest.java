package net.yudichev.jiotty.common.async;

import com.google.inject.Guice;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.time.TimeModule;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

class JobSchedulerModuleTest {
    @Test
    void injector() {
        ExposedKeyModule<JobScheduler> module = JobSchedulerModule.builder()
                                                                  .withZoneId(literally(ZoneId.systemDefault()))
                                                                  .build();

        Guice.createInjector(TimeModule.builder().build(), ExecutorModule.builder().build(), module).getBinding(module.getExposedKey());
    }
}