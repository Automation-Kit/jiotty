package net.yudichev.jiotty.connector.ip;

import com.google.common.reflect.TypeToken;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import static com.google.common.base.Preconditions.checkNotNull;

public final class HostMonitorModule extends BaseExposedKeyModule<HostMonitor> {
    private final BindingSpec<List<String>> hostnameSpec;
    private final BindingSpec<String> nameSpec;
    private final BindingSpec<Duration> toleranceSpec;

    private HostMonitorModule(BindingSpec<List<String>> hostnameSpec,
                              BindingSpec<String> nameSpec,
                              BindingSpec<Duration> toleranceSpec,
                              SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.hostnameSpec = checkNotNull(hostnameSpec);
        this.nameSpec = nameSpec;
        this.toleranceSpec = checkNotNull(toleranceSpec);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        hostnameSpec.bind(new TypeLiteral<>() {})
                    .annotatedWith(HostMonitorImpl.Hostnames.class)
                    .installedBy(this::installLifecycleComponentModule);
        nameSpec.bind(String.class)
                .annotatedWith(HostMonitorImpl.Name.class)
                .installedBy(this::installLifecycleComponentModule);
        toleranceSpec.bind(Duration.class)
                     .annotatedWith(HostMonitorImpl.Tolerance.class)
                     .installedBy(this::installLifecycleComponentModule);
        bind(exposedKey).to(registerLifecycleComponent(HostMonitorImpl.class));
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<HostMonitor, Builder> {
        private BindingSpec<List<String>> hostnameSpec;
        private BindingSpec<String> nameSpec;
        private BindingSpec<Duration> toleranceSpec = BindingSpec.literally(Duration.ofSeconds(30));

        public Builder setHostnames(BindingSpec<List<String>> hostnameSpec) {
            this.hostnameSpec = checkNotNull(hostnameSpec);
            return this;
        }

        public Builder withName(BindingSpec<String> nameSpec) {
            this.nameSpec = checkNotNull(nameSpec);
            return this;
        }

        public Builder withTolerance(BindingSpec<Duration> toleranceSpec) {
            this.toleranceSpec = checkNotNull(toleranceSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<HostMonitor> build() {
            return new HostMonitorModule(hostnameSpec,
                                         nameSpec == null
                                         ? hostnameSpec.map(new TypeToken<>() {}, new TypeToken<>() {}, Objects::toString)
                                         : nameSpec,
                                         toleranceSpec,
                                         specifiedAnnotation());
        }
    }
}
