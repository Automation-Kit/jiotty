package net.yudichev.jiotty.connector.icloud.calendar;

import com.google.inject.Guice;
import com.google.inject.Key;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.time.TimeModule;
import net.yudichev.jiotty.common.time.calendar.CalendarService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.annotation.Annotation;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static org.assertj.core.api.Assertions.assertThat;

class IcloudCalendarModuleTest {
    /// Builds the module under a caller-specified annotation (the [ExposedKeyModule] disambiguation affordance) and instantiates the resulting
    /// [CalendarService], covering the default executor spec. `getInstance` (not `getBinding`) so a throwing or unsatisfiable provider anywhere in the graph
    /// fails the test.
    @Test
    void configuresResolvableServiceUnderTheSpecifiedAnnotation() {
        Annotation annotation = uniqueAnnotation();
        ExposedKeyModule<CalendarService> module = IcloudCalendarModule.builder()
                                                                       .setUsername(literally("test@example.com"))
                                                                       .setPassword(literally("test-password"))
                                                                       .withLogSubjectId(literally("test-user"))
                                                                       .withAnnotation(forAnnotation(annotation))
                                                                       .build();
        var injector = Guice.createInjector(TimeModule.builder().build(), ExecutorModule.builder().build(), module);

        assertThat(module.getExposedKey()).isEqualTo(Key.get(CalendarService.class, annotation));
        assertThat(injector.getInstance(module.getExposedKey())).isInstanceOf(IcloudCalendarService.class);
    }

    /// The subject id must reach the executor thread name, since that is what keeps concurrent per-user instances apart in the log (`[%t]`) and in the
    /// executor's `name` metric label. A blank subject id means single-instance use, where the bare name is unambiguous.
    @ParameterizedTest
    @CsvSource({"user-1, Icloud-Calendar-user-1",
            "'',     Icloud-Calendar"})
    void threadNameCarriesTheSubjectId(String logSubjectId, String expectedThreadName) {
        assertThat(IcloudCalendarModule.threadName(logSubjectId)).isEqualTo(expectedThreadName);
    }

    /// Two instances installed side by side, as the per-user graph does, resolve independently: the default executor spec carries a unique annotation, so the
    /// two [ExecutorProviderModule] installs each get their own key.
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
                                   .withLogSubjectId(literally(logSubjectId))
                                   .withAnnotation(forAnnotation(uniqueAnnotation()))
                                   .build();
    }
}
