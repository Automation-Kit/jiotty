package net.yudichev.jiotty.user.ui;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.TypeLiteral;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Inject;
import net.yudichev.jiotty.adminalerts.LoggingAdminAlertServiceModule;
import net.yudichev.jiotty.common.app.Application;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.metrics.NoopMeterRegistry;
import net.yudichev.jiotty.common.time.TimeModule;
import net.yudichev.jiotty.persistence.varstore.InMemoryVarStore;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import net.yudichev.jiotty.user.push.PushDeviceStore;
import net.yudichev.jiotty.user.ui.options.Option;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.boundTo;
import static net.yudichev.jiotty.common.inject.BindingSpec.exposedBy;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

class UIServerModuleTest {
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);

    private Injector injector;
    /// Null when [#setUp] failed before assigning it, which is the one path where [#tearDown] has nothing to stop.
    private @Nullable List<LifecycleComponent> components;

    /// Starts every component in binding order — the order [Application] starts them in — so the test exercises the install order inside [UIServerModule]
    /// rather than only its bindings.
    @BeforeEach
    void setUp() {
        injector = injectorWith(moduleBuilder());
        components = injector.findBindingsByType(new TypeLiteral<LifecycleComponent>() {})
                             .stream()
                             .map(binding -> injector.getInstance(binding.getKey()))
                             .toList();
        components.forEach(LifecycleComponent::start);
    }

    @AfterEach
    void tearDown() {
        if (components != null) {
            components.reversed().forEach(LifecycleComponent::stop);
        }
    }

    /// The push-device store resolves its executor when it starts, so the executor's own component has to have started first. Driving a store call through
    /// that executor pins the install order: a store registered ahead of its executor fails here rather than in production.
    @Test
    void startsThePushDeviceStoreOnAWorkingExecutor() {
        assertThat(injector.getInstance(PushDeviceStore.class).list()).succeedsWithin(CALL_TIMEOUT)
                                                                      .asInstanceOf(LIST)
                                                                      .isEmpty();
    }

    @Test
    void registersWithTheRealServerWhenNoOtherIsNamed() {
        assertThat(injector.getInstance(UIServer.class)).isInstanceOf(UIServerImpl.class);
    }

    /// A replacement is resolved in this module's scope, which is what lets one that wraps the real server reach it.
    @Test
    void registersWithTheNamedServerWhichCanWrapTheRealOne() {
        Injector wrappedInjector = injectorWith(moduleBuilder().withUIServer(boundTo(PassThroughUIServer.class)));

        assertThat(wrappedInjector.getInstance(UIServer.class)).isInstanceOfSatisfying(
                PassThroughUIServer.class, wrapper -> assertThat(wrapper.delegate).isInstanceOf(UIServerImpl.class));
    }

    /// The app registers with one server, however many components ask for it.
    @Test
    void registersWithOneServerInstance() {
        Injector wrappedInjector = injectorWith(moduleBuilder().withUIServer(boundTo(PassThroughUIServer.class)));

        assertThat(wrappedInjector.getInstance(UIServer.class)).isSameAs(wrappedInjector.getInstance(UIServer.class));
    }

    private static UIServerModule.Builder moduleBuilder() {
        return UIServerModule.builder()
                             .setAdminAlertService(exposedBy(LoggingAdminAlertServiceModule.builder().build()))
                             .withThreadNameSuffix(literally("test-user"));
    }

    private static Injector injectorWith(UIServerModule.Builder moduleBuilder) {
        return Guice.createInjector(ExecutorModule.builder().build(),
                                    TimeModule.builder().build(),
                                    new AbstractModule() {
                                        @Override
                                        protected void configure() {
                                            bind(MeterRegistry.class).toInstance(new NoopMeterRegistry());
                                            bind(VarStore.class).toInstance(new InMemoryVarStore());
                                        }
                                    },
                                    moduleBuilder.build());
    }

    static final class PassThroughUIServer implements UIServer {
        private final UIServer delegate;

        @Inject
        PassThroughUIServer(UIServerImpl delegate) {
            this.delegate = checkNotNull(delegate);
        }

        @Override
        public Closeable registerDisplayable(Displayable displayable) {
            return delegate.registerDisplayable(displayable);
        }

        @Override
        public Closeable registerOption(Option<?> option) {
            return delegate.registerOption(option);
        }
    }
}
