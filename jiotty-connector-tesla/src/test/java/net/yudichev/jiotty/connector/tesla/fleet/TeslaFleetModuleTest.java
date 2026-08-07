package net.yudichev.jiotty.connector.tesla.fleet;

import com.google.common.collect.ImmutableSet;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Key;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.time.TimeModule;
import net.yudichev.jiotty.persistence.varstore.InMemoryVarStore;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.annotation.Annotation;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static org.assertj.core.api.Assertions.assertThat;

class TeslaFleetModuleTest {
    /// Requesting `offline_access` is what makes the token endpoint return a refresh token, so it is appended when the caller omits it and left alone when the
    /// caller already asked for it — no duplicate.
    @Test
    void scopeAlwaysRequestsOfflineAccess() {
        assertThat(TeslaFleetModule.scope(ImmutableSet.of("vehicle_cmds"))).isEqualTo("vehicle_cmds offline_access");
        assertThat(TeslaFleetModule.scope(ImmutableSet.of("vehicle_cmds", "offline_access"))).isEqualTo("vehicle_cmds offline_access");
    }

    /// Builds the module under a caller-specified annotation (the [ExposedKeyModule] disambiguation affordance) and instantiates the resulting [TeslaFleet],
    /// exercising the embedded token manager and the derived exposed key. Covers both the UI-driven mode and the `localLogin` mode (loopback token manager)
    /// used by the manual runner. `getInstance` (not `getBinding`) so a throwing or unsatisfiable provider anywhere in the graph fails the test.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void configuresResolvableFleetUnderTheSpecifiedAnnotation(boolean localLogin) {
        Annotation annotation = uniqueAnnotation();
        ExposedKeyModule<TeslaFleet> module = TeslaFleetModule.builder()
                                                              .setClientId(literally("test-client-id"))
                                                              .setClientSecret(literally("test-client-secret"))
                                                              .withLogSubjectId(literally("test-user"))
                                                              .withVarStore(literally(new InMemoryVarStore()))
                                                              .withLocalLogin(localLogin)
                                                              .withAnnotation(forAnnotation(annotation))
                                                              .build();
        var injector = Guice.createInjector(TimeModule.builder().build(), ExecutorModule.builder().build(), module);

        assertThat(module.getExposedKey()).isEqualTo(Key.get(TeslaFleet.class, annotation));
        assertThat(injector.getInstance(module.getExposedKey())).isInstanceOf(TeslaFleetImpl.class);
    }

    /// Omitting the var store falls back to whatever [VarStore] the surrounding graph binds, so the connector works in an application that already has one.
    @Test
    void fallsBackToTheAmbientVarStore() {
        ExposedKeyModule<TeslaFleet> module = TeslaFleetModule.builder()
                                                              .setClientId(literally("test-client-id"))
                                                              .setClientSecret(literally("test-client-secret"))
                                                              .withAnnotation(forAnnotation(uniqueAnnotation()))
                                                              .build();
        var injector = Guice.createInjector(TimeModule.builder().build(),
                                            ExecutorModule.builder().build(),
                                            new AbstractModule() {
                                                @Override
                                                protected void configure() {
                                                    bind(VarStore.class).toInstance(new InMemoryVarStore());
                                                }
                                            },
                                            module);

        assertThat(injector.getInstance(module.getExposedKey())).isInstanceOf(TeslaFleetImpl.class);
    }

    /// Two instances installed side by side, as the per-user graph does, resolve independently: each carries its own exposed-key annotation and its own
    /// token-manager API name.
    @Test
    void supportsTwoInstancesSideBySide() {
        ExposedKeyModule<TeslaFleet> first = moduleForSubject("user-1");
        ExposedKeyModule<TeslaFleet> second = moduleForSubject("user-2");
        var injector = Guice.createInjector(TimeModule.builder().build(), ExecutorModule.builder().build(), first, second);

        assertThat(injector.getInstance(first.getExposedKey())).isNotSameAs(injector.getInstance(second.getExposedKey()));
    }

    private static ExposedKeyModule<TeslaFleet> moduleForSubject(String logSubjectId) {
        return TeslaFleetModule.builder()
                               .setClientId(literally("test-client-id"))
                               .setClientSecret(literally("test-client-secret"))
                               .withLogSubjectId(literally(logSubjectId))
                               .withVarStore(literally(new InMemoryVarStore()))
                               .withAnnotation(forAnnotation(uniqueAnnotation()))
                               .build();
    }
}
