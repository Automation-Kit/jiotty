package net.yudichev.jiotty.connector.sonyprojector;

import com.google.common.reflect.TypeToken;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.lang.TypedBuilder;

import java.time.Duration;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

public final class SonyProjectorClientModule extends BaseLifecycleComponentModule implements ExposedKeyModule<SonyProjectorClient> {

    private final BindingSpec<String> hostSpec;
    private final BindingSpec<Integer> portSpec;
    private final BindingSpec<Duration> timeoutSpec;
    private final BindingSpec<Optional<String>> passwordSpec;

    private SonyProjectorClientModule(BindingSpec<String> hostSpec,
                                      BindingSpec<Integer> portSpec,
                                      BindingSpec<Duration> timeoutSpec,
                                      BindingSpec<Optional<String>> passwordSpec) {
        this.hostSpec = checkNotNull(hostSpec);
        this.portSpec = checkNotNull(portSpec);
        this.timeoutSpec = checkNotNull(timeoutSpec);
        this.passwordSpec = checkNotNull(passwordSpec);
    }

    @Override
    protected void configure() {
        hostSpec.bind(String.class)
                .annotatedWith(SonyProjectorClientImpl.Host.class)
                .installedBy(this::installLifecycleComponentModule);
        portSpec.bind(Integer.class)
                .annotatedWith(SonyProjectorClientImpl.Port.class)
                .installedBy(this::installLifecycleComponentModule);
        timeoutSpec.bind(Duration.class)
                   .annotatedWith(SonyProjectorClientImpl.Timeout.class)
                   .installedBy(this::installLifecycleComponentModule);
        passwordSpec.bind(new TypeLiteral<>() {})
                    .annotatedWith(SonyProjectorClientImpl.Password.class)
                    .installedBy(this::installLifecycleComponentModule);

        bind(getExposedKey()).to(registerLifecycleComponent(SonyProjectorClientImpl.class));
        expose(getExposedKey());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder implements TypedBuilder<ExposedKeyModule<SonyProjectorClient>> {
        private BindingSpec<String> hostSpec;
        private BindingSpec<Integer> portSpec = literally(53595);
        private BindingSpec<Duration> timeoutSpec = literally(Duration.ofSeconds(4));
        private BindingSpec<Optional<String>> passwordSpec = literally(Optional.empty());

        public Builder setHost(BindingSpec<String> hostSpec) {
            this.hostSpec = checkNotNull(hostSpec);
            return this;
        }

        public Builder withPort(BindingSpec<Integer> portSpec) {
            this.portSpec = checkNotNull(portSpec);
            return this;
        }

        public Builder withTimeout(BindingSpec<Duration> timeoutSpec) {
            this.timeoutSpec = checkNotNull(timeoutSpec);
            return this;
        }

        public Builder withPassword(BindingSpec<String> passwordSpec) {
            this.passwordSpec = checkNotNull(passwordSpec).map(new TypeToken<>() {}, new TypeToken<>() {}, Optional::of);
            return this;
        }

        @Override
        public ExposedKeyModule<SonyProjectorClient> build() {
            checkNotNull(hostSpec, "hostSpec");
            return new SonyProjectorClientModule(hostSpec, portSpec, timeoutSpec, passwordSpec);
        }
    }
}
