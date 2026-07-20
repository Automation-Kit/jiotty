package net.yudichev.jiotty.connector.google.calendar;

import com.google.inject.Guice;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.time.TimeModule;
import net.yudichev.jiotty.common.time.calendar.CalendarService;
import net.yudichev.jiotty.persistence.varstore.InMemoryVarStore;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static org.assertj.core.api.Assertions.assertThat;

class GoogleCalendarModuleTest {
    /// The subject id must reach the embedded token manager's API name, since that is what keeps concurrent per-user instances apart in the log and in the
    /// token manager's own executor thread name. A blank subject id means single-instance use, where the bare name is unambiguous.
    @ParameterizedTest
    @CsvSource({"user-1, GoogleCalendar-user-1",
            "'',     GoogleCalendar"})
    void apiNameCarriesTheSubjectId(String logSubjectId, String expectedApiName) {
        assertThat(GoogleCalendarModule.apiName(logSubjectId)).isEqualTo(expectedApiName);
    }

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
        Guice.createInjector(new TimeModule(), new ExecutorModule(), module).getInstance(module.getExposedKey());
    }
}
