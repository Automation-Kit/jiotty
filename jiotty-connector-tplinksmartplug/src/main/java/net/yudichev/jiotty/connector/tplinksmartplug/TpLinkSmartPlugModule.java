package net.yudichev.jiotty.connector.tplinksmartplug;

import com.google.inject.Key;
import net.yudichev.jiotty.appliance.Appliance;
import net.yudichev.jiotty.appliance.ApplianceModule;
import net.yudichev.jiotty.common.async.backoff.BackOffConfig;
import net.yudichev.jiotty.common.async.backoff.BackingOffExceptionHandlerModule;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutorModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import java.time.Duration;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.boundTo;
import static net.yudichev.jiotty.common.inject.BindingSpec.exposedBy;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;

public abstract class TpLinkSmartPlugModule extends ApplianceModule {

    private final BindingSpec<String> nameSpec;

    private TpLinkSmartPlugModule(SpecifiedAnnotation targetAnnotation, BindingSpec<String> nameSpec) {
        super(targetAnnotation);
        this.nameSpec = checkNotNull(nameSpec);
    }

    @Override
    protected final Key<? extends Appliance> configureDependencies() {
        nameSpec.bind(String.class)
                .annotatedWith(Bindings.Name.class)
                .installedBy(this::installLifecycleComponentModule);
        installLifecycleComponentModule(
                RetryableOperationExecutorModule
                        .builder()
                        .withAnnotation(forAnnotation(Bindings.Dependency.class))
                        .setBackingOffExceptionHandler(
                                exposedBy(BackingOffExceptionHandlerModule
                                                  .builder()
                                                  .setRetryableExceptionPredicate(boundTo(RetryableExceptionPredicate.class))
                                                  .withConfig(literally(BackOffConfig.builder().setInitialInterval(Duration.ofMillis(500))
                                                                                     .setMaxInterval(Duration.ofSeconds(30))
                                                                                     .setMaxElapsedTime(Duration.ofHours(3))
                                                                                     .build()))
                                                  .build()))
                        .build());
        return doConfigureDependencies();
    }

    protected abstract Key<? extends Appliance> doConfigureDependencies();

    public static CloudBuilder cloudConnectionBuilder() {
        return new CloudBuilder();
    }

    public static LocalBuilder localConnectionBuilder() {
        return new LocalBuilder();
    }

    private static final class ClounTpLinkSmartPlugModule extends TpLinkSmartPlugModule {
        private final BindingSpec<String> usernameSpec;
        private final BindingSpec<String> passwordSpec;
        private final BindingSpec<String> termIdSpec;
        private final BindingSpec<String> deviceIdSpec;

        private ClounTpLinkSmartPlugModule(BindingSpec<String> usernameSpec,
                                           BindingSpec<String> passwordSpec,
                                           BindingSpec<String> termIdSpec,
                                           BindingSpec<String> deviceIdSpec,
                                           BindingSpec<String> nameSpec,
                                           SpecifiedAnnotation targetAnnotation) {
            super(targetAnnotation, nameSpec);
            this.usernameSpec = checkNotNull(usernameSpec);
            this.passwordSpec = checkNotNull(passwordSpec);
            this.termIdSpec = checkNotNull(termIdSpec);
            this.deviceIdSpec = checkNotNull(deviceIdSpec);
        }

        @Override
        protected Key<? extends Appliance> doConfigureDependencies() {
            usernameSpec.bind(String.class)
                        .annotatedWith(CloudTpLinkSmartPlug.Username.class)
                        .installedBy(this::installLifecycleComponentModule);
            passwordSpec.bind(String.class)
                        .annotatedWith(CloudTpLinkSmartPlug.Password.class)
                        .installedBy(this::installLifecycleComponentModule);
            termIdSpec.bind(String.class)
                      .annotatedWith(CloudTpLinkSmartPlug.TermId.class)
                      .installedBy(this::installLifecycleComponentModule);
            deviceIdSpec.bind(String.class)
                        .annotatedWith(CloudTpLinkSmartPlug.DeviceId.class)
                        .installedBy(this::installLifecycleComponentModule);
            return registerLifecycleComponent(CloudTpLinkSmartPlug.class);
        }
    }

    private static class LocalTpLinkSmartPlugModule extends TpLinkSmartPlugModule {
        private final BindingSpec<String> hostSpec;

        public LocalTpLinkSmartPlugModule(BindingSpec<String> nameSpec, BindingSpec<String> hostSpec, SpecifiedAnnotation specifiedAnnotation) {
            super(specifiedAnnotation, nameSpec);
            this.hostSpec = checkNotNull(hostSpec);
        }

        @Override
        protected Key<? extends Appliance> doConfigureDependencies() {
            hostSpec.bind(String.class)
                    .annotatedWith(LocalTpLinkSmartPlug.Host.class)
                    .installedBy(this::installLifecycleComponentModule);
            return registerLifecycleComponent(LocalTpLinkSmartPlug.class);
        }
    }

    public abstract static class Builder<T extends Builder<T>> extends BaseModuleBuilder<Appliance, T> {
        protected BindingSpec<String> nameSpec;

        private Builder() {
        }

        protected abstract T thisBuilder();

        public T withName(BindingSpec<String> nameSpec) {
            this.nameSpec = checkNotNull(nameSpec);
            return thisBuilder();
        }
    }

    @SuppressWarnings("UnnecessarySuperQualifier") // to prevent another inspection
    public static class CloudBuilder extends Builder<CloudBuilder> {
        private BindingSpec<String> usernameSpec;
        private BindingSpec<String> passwordSpec;
        private BindingSpec<String> termIdSpec;
        private BindingSpec<String> deviceIdSpec;

        public CloudBuilder setUsername(BindingSpec<String> usernameSpec) {
            this.usernameSpec = checkNotNull(usernameSpec);
            return this;
        }

        public CloudBuilder setPassword(BindingSpec<String> passwordSpec) {
            this.passwordSpec = passwordSpec;
            return this;
        }

        public CloudBuilder setTermId(BindingSpec<String> termIdSpec) {
            this.termIdSpec = termIdSpec;
            return this;
        }

        public CloudBuilder setDeviceId(BindingSpec<String> deviceIdSpec) {
            this.deviceIdSpec = checkNotNull(deviceIdSpec);
            return this;
        }

        @Override
        protected CloudBuilder thisBuilder() {
            return this;
        }

        @Override
        public ExposedKeyModule<Appliance> build() {
            if (super.nameSpec == null) {
                super.nameSpec = deviceIdSpec;
            }
            return new ClounTpLinkSmartPlugModule(usernameSpec, passwordSpec, termIdSpec, deviceIdSpec, super.nameSpec, specifiedAnnotation());
        }
    }

    @SuppressWarnings("UnnecessarySuperQualifier") // to prevent another inspection
    public static final class LocalBuilder extends Builder<LocalBuilder> {
        private BindingSpec<String> hostSpec;

        @Override
        protected LocalBuilder thisBuilder() {
            return this;
        }

        public LocalBuilder setHost(BindingSpec<String> hostSpec) {
            this.hostSpec = checkNotNull(hostSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<Appliance> build() {
            if (super.nameSpec == null) {
                super.nameSpec = hostSpec;
            }
            return new LocalTpLinkSmartPlugModule(super.nameSpec, hostSpec, specifiedAnnotation());
        }
    }
}
