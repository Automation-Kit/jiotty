package net.yudichev.jiotty.connector.google.calendar;

import com.google.inject.Guice;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.time.TimeModule;
import net.yudichev.jiotty.common.time.calendar.CalendarService;
import net.yudichev.jiotty.persistence.varstore.InMemoryVarStore;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;

class GoogleCalendarModuleTest {
    /// Builds the module under a caller-specified annotation (the [ExposedKeyModule] disambiguation affordance) and instantiates the resulting
    /// [CalendarService] — exercising the embedded token manager and the derived exposed key. Covers the UI-driven public-client mode (no secret) and the
    /// `localLogin` mode (loopback token manager + client secret) used by the manual runner. `getInstance` (not `getBinding`) so a throwing or unsatisfiable
    /// provider anywhere in the graph fails the test.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void configuresResolvableClient(boolean localLogin) {
        GoogleCalendarModule.Builder builder = GoogleCalendarModule.builder()
                                                                   .setClientId(literally("test-client-id"))
                                                                   .setRedirectUri(literally("joulary://oauth_redirect/google"))
                                                                   .withLocalLogin(localLogin)
                                                                   .withLogSubjectId(literally("test-user"))
                                                                   .withVarStore(literally(new InMemoryVarStore()))
                                                                   .withAnnotation(forAnnotation(uniqueAnnotation()));
        if (localLogin) {
            // a Desktop-app client (loopback login) carries a secret, like the manual runner supplies
            builder.withClientSecret(literally("test-client-secret"));
        }
        ExposedKeyModule<CalendarService> module = builder.build();
        // getInstance (not getBinding) so a throwing or unsatisfiable provider anywhere in the graph fails the test; construction succeeding is the assertion.
        Guice.createInjector(TimeModule.builder().build(), ExecutorModule.builder().build(), module).getInstance(module.getExposedKey());
    }
}
