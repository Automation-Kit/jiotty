package net.yudichev.jiotty.connector.icloud.calendar;

import com.google.inject.Guice;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.common.time.TimeModule;
import net.yudichev.jiotty.common.time.calendar.CalendarService;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.time.Duration;

import static net.yudichev.jiotty.common.inject.BindingSpec.exposedBy;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static org.assertj.core.api.Assertions.assertThat;

class IcloudCalendarModuleTest {
    /// Builds the module under a caller-specified annotation (the [ExposedKeyModule] disambiguation affordance) and instantiates the resulting
    /// [CalendarService]. `getInstance` (not `getBinding`) so a throwing or unsatisfiable provider anywhere in the graph fails the test.
    @Test
    void configuresResolvableServiceUnderTheSpecifiedAnnotation() {
        Annotation annotation = uniqueAnnotation();
        ExposedKeyModule<CalendarService> module = IcloudCalendarModule.builder()
                                                                       .setUsername(literally("test@example.com"))
                                                                       .setPassword(literally("test-password"))
                                                                       .setExecutor(createExecutorSpec())
                                                                       .withLogSubjectId(literally("test-user"))
                                                                       .withTimeout(literally(Duration.ofSeconds(5)))
                                                                       .withAnnotation(forAnnotation(annotation))
                                                                       .build();
        var injector = Guice.createInjector(TimeModule.builder().build(), ExecutorModule.builder().build(), module);

        assertThat(module.getExposedKey()).isEqualTo(Key.get(CalendarService.class, annotation));
        assertThat(injector.getInstance(module.getExposedKey())).isInstanceOf(IcloudCalendarService.class);
        assertThat(injector.findBindingsByType(new TypeLiteral<LifecycleComponent>() {})).isNotEmpty();
    }

    /// Two instances installed side by side, as a caller installing one per subject does, resolve independently.
    @Test
    void supportsTwoInstancesSideBySide() {
        ExposedKeyModule<CalendarService> first = moduleForSubject("user-1");
        ExposedKeyModule<CalendarService> second = moduleForSubject("user-2");
        var injector = Guice.createInjector(TimeModule.builder().build(), ExecutorModule.builder().build(), first, second);

        assertThat(injector.getInstance(first.getExposedKey())).isNotSameAs(injector.getInstance(second.getExposedKey()));
    }

    private static ExposedKeyModule<CalendarService> moduleForSubject(String logSubjectId) {
        return IcloudCalendarModule.builder()
                                   .setUsername(literally(logSubjectId + "@example.com"))
                                   .setPassword(literally("test-password"))
                                   .setExecutor(createExecutorSpec())
                                   .withLogSubjectId(literally(logSubjectId))
                                   .withAnnotation(forAnnotation(uniqueAnnotation()))
                                   .build();
    }

    private static BindingSpec<SchedulingExecutor> createExecutorSpec() {
        return exposedBy(ExecutorProviderModule.builder()
                                               .setThreadName(literally("test-executor"))
                                               .withAnnotation(forAnnotation(uniqueAnnotation()))
                                               .build());
    }
}
