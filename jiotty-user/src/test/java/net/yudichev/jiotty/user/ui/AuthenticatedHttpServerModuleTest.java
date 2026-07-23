package net.yudichev.jiotty.user.ui;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.TypeLiteral;
import io.micrometer.core.instrument.MeterRegistry;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.common.metrics.NoopMeterRegistry;
import net.yudichev.jiotty.common.time.TimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class AuthenticatedHttpServerModuleTest {

    /// Resolving the graph proves the pre-auth admission limits reach [PreAuthAdmissionControl]: its constructor takes each one under its own binding
    /// annotation, so a missing or mis-annotated limit fails injector creation here rather than at first request.
    @Test
    void bindsItsLifecycleComponentsWithDefaultAdmissionLimits(@Mock UserTokenAuthoriser userTokenAuthoriser) {
        Injector injector = injectorFor(AuthenticatedHttpServerModule.builder()
                                                                     .setUserTokenAuthoriser(literally(userTokenAuthoriser))
                                                                     .build());

        assertThat(injector.findBindingsByType(new TypeLiteral<LifecycleComponent>() {})).isNotEmpty();
    }

    /// The deployed server overrides every admission limit and adds its own mounts, so the builder has to accept them all and still produce a resolvable
    /// graph — each added mount is bound under its own annotation, which is where a duplicate-binding mistake would surface.
    @Test
    void bindsItsLifecycleComponentsWithConfiguredLimitsAndMounts(@Mock UserTokenAuthoriser userTokenAuthoriser,
                                                                  @Mock ServletMount servletMount,
                                                                  @Mock ServletMount otherServletMount) {
        Injector injector = injectorFor(AuthenticatedHttpServerModule.builder()
                                                                     .setUserTokenAuthoriser(literally(userTokenAuthoriser))
                                                                     .withListenPort(literally(0))
                                                                     .withTrustProxyHeaders(literally(true))
                                                                     .withPreAuthRequestsPerSecond(literally(3.5))
                                                                     .withMaxInFlightVerifications(literally(7))
                                                                     .withPerUidRequestsPerSecond(literally(4.0))
                                                                     .withPerUidBurst(literally(12.0))
                                                                     .addServletMount(literally(servletMount))
                                                                     .addServletMount(literally(otherServletMount))
                                                                     .build());

        assertThat(injector.findBindingsByType(new TypeLiteral<LifecycleComponent>() {})).isNotEmpty();
    }

    private static Injector injectorFor(AuthenticatedHttpServerModule module) {
        return Guice.createInjector(new ExecutorModule(),
                                    new TimeModule(),
                                    new AbstractModule() {
                                        @Override
                                        protected void configure() {
                                            bind(MeterRegistry.class).toInstance(new NoopMeterRegistry());
                                        }
                                    },
                                    module);
    }
}
