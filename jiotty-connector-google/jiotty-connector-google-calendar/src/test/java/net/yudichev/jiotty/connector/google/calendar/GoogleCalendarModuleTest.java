package net.yudichev.jiotty.connector.google.calendar;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.common.time.TimeModule;
import net.yudichev.jiotty.common.time.calendar.CalendarService;
import net.yudichev.jiotty.persistence.varstore.InMemoryVarStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static net.yudichev.jiotty.common.inject.BindingSpec.exposedBy;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static org.assertj.core.api.Assertions.assertThat;

class GoogleCalendarModuleTest {
    /// Builds the module under a caller-specified annotation (the [ExposedKeyModule] disambiguation affordance) and instantiates the resulting
    /// [CalendarService] — exercising the embedded token manager and the derived exposed key. Covers the UI-driven public-client mode (no secret) and the
    /// `localLogin` mode (loopback token manager + client secret) used by the manual runner.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void configuresResolvableClient(boolean localLogin) {
        GoogleCalendarModule.Builder builder = newBuilder().withLocalLogin(localLogin);
        if (localLogin) {
            // a Desktop-app client (loopback login) carries a secret, like the manual runner supplies
            builder.withClientSecret(literally("test-client-secret"));
        }
        assertResolves(builder.build());
    }

    @Test
    void configuresResolvableClientWithATimeout() {
        assertResolves(newBuilder().withTimeout(literally(Duration.ofSeconds(5))).build());
    }

    /// [Injector#getInstance(Key)], not [Injector#getBinding(Key)], so a throwing or unsatisfiable provider anywhere in the graph fails the test.
    private static void assertResolves(ExposedKeyModule<CalendarService> module) {
        var injector = Guice.createInjector(TimeModule.builder().build(), ExecutorModule.builder().build(), module);
        injector.getInstance(module.getExposedKey());
        assertThat(injector.findBindingsByType(new TypeLiteral<LifecycleComponent>() {})).isNotEmpty();
    }

    private static GoogleCalendarModule.Builder newBuilder() {
        return GoogleCalendarModule.builder()
                                   .setClientId(literally("test-client-id"))
                                   .setRedirectUri(literally("joulary://oauth_redirect/google"))
                                   .setExecutor(exposedBy(ExecutorProviderModule.builder()
                                                                                .setThreadName(literally("test-executor"))
                                                                                .withAnnotation(forAnnotation(uniqueAnnotation()))
                                                                                .build()))
                                   .withLogSubjectId(literally("test-user"))
                                   .withVarStore(literally(new InMemoryVarStore()))
                                   .withAnnotation(forAnnotation(uniqueAnnotation()));
    }
}
