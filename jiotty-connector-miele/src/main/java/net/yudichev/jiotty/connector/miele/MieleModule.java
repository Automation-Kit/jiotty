package net.yudichev.jiotty.connector.miele;

import net.yudichev.jiotty.common.async.backoff.BackOffConfig;
import net.yudichev.jiotty.common.async.backoff.BackingOffExceptionHandlerModule;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutorModule;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.lang.TypedBuilder;
import net.yudichev.jiotty.security.OAuth2TokenManagerModule;

import java.time.Duration;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.exposedBy;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;

public final class MieleModule extends BaseLifecycleComponentModule {
    private final BindingSpec<String> deviceIdSpec;
    private final BindingSpec<String> clientIdSpec;
    private final BindingSpec<String> clientSecretSpec;

    private MieleModule(BindingSpec<String> deviceIdSpec, BindingSpec<String> clientIdSpec, BindingSpec<String> clientSecretSpec) {
        this.deviceIdSpec = checkNotNull(deviceIdSpec);
        this.clientIdSpec = checkNotNull(clientIdSpec);
        this.clientSecretSpec = checkNotNull(clientSecretSpec);
    }

    @Override
    protected void configure() {
        installLifecycleComponentModule(
                RetryableOperationExecutorModule
                        .builder()
                        .setBackingOffExceptionHandler(exposedBy(BackingOffExceptionHandlerModule
                                                                         .builder()
                                                                         .setRetryableExceptionPredicate(literally(throwable -> true))
                                                                         .withConfig(literally(BackOffConfig.builder()
                                                                                                            .setInitialInterval(Duration.ofMillis(500))
                                                                                                            .setMaxInterval(Duration.ofSeconds(2))
                                                                                                            .setMaxElapsedTime(Duration.ofSeconds(10))
                                                                                                            .build()))
                                                                         .build()))
                        .withAnnotation(forAnnotation(MieleDishwasherImpl.Dependency.class))
                        .build());

        installLifecycleComponentModule(OAuth2TokenManagerModule.builder()
                                                                .setApiName(literally("Miele"))
                                                                .setLoginUrl(literally("https://api.mcs3.miele.com/thirdparty/login"))
                                                                .setTokenUrlSpec(literally("https://api.mcs3.miele.com/thirdparty/token"))
                                                                .setScope(literally("IDENTIFY_APPLIANCES"))
                                                                .setClientId(clientIdSpec)
                                                                .setClientSecret(clientSecretSpec)
                                                                .withAnnotation(forAnnotation(MieleDishwasherImpl.Dependency.class))
                                                                .build());

        deviceIdSpec.bind(String.class)
                    .annotatedWith(MieleDishwasherImpl.DeviceId.class)
                    .installedBy(this::installLifecycleComponentModule);

        bind(MieleDishwasher.class).to(registerLifecycleComponent(MieleDishwasherImpl.class));
        expose(MieleDishwasher.class);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder implements TypedBuilder<MieleModule> {
        private BindingSpec<String> clientIdSpec;
        private BindingSpec<String> clientSecretSpec;
        private BindingSpec<String> deviceIdSpec;

        public Builder setDeviceId(BindingSpec<String> deviceIdSpec) {
            this.deviceIdSpec = checkNotNull(deviceIdSpec);
            return this;
        }

        public Builder setClientSecret(BindingSpec<String> clientSecretSpec) {
            this.clientSecretSpec = clientSecretSpec;
            return this;
        }

        public Builder setClientId(BindingSpec<String> clientIdSpec) {
            this.clientIdSpec = clientIdSpec;
            return this;
        }

        @Override
        public MieleModule build() {
            return new MieleModule(deviceIdSpec, clientIdSpec, clientSecretSpec);
        }
    }
}
