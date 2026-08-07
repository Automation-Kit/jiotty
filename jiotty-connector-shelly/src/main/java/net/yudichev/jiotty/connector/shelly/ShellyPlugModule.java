package net.yudichev.jiotty.connector.shelly;

import net.yudichev.jiotty.common.async.backoff.BackOffConfig;
import net.yudichev.jiotty.common.async.backoff.BackingOffExceptionHandlerModule;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutorModule;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import java.time.Duration;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.exposedBy;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;

public final class ShellyPlugModule extends BaseExposedKeyModule<ShellyPlug> {
    private final BindingSpec<String> hostSpec;
    private final BindingSpec<BackOffConfig> backoffConfigSpec;

    private ShellyPlugModule(BindingSpec<String> hostSpec, BindingSpec<BackOffConfig> backoffConfigSpec, SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.hostSpec = checkNotNull(hostSpec);
        this.backoffConfigSpec = checkNotNull(backoffConfigSpec);
    }

    @Override
    protected void configure() {
        installLifecycleComponentModule(
                RetryableOperationExecutorModule
                        .builder()
                        .setBackingOffExceptionHandler(exposedBy(BackingOffExceptionHandlerModule
                                                                         .builder()
                                                                         .setRetryableExceptionPredicate(literally(throwable -> true))
                                                                         .withConfig(backoffConfigSpec)
                                                                         .build()))
                        .withAnnotation(forAnnotation(ShellyPlugImpl.Dependency.class))
                        .build());

        hostSpec.bind(String.class)
                .annotatedWith(ShellyPlugImpl.Host.class)
                .installedBy(this::installLifecycleComponentModule);

        bind(exposedKey).to(registerLifecycleComponent(ShellyPlugImpl.class));
        expose(exposedKey);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends BaseModuleBuilder<ShellyPlug, Builder> {
        private BindingSpec<String> hostSpec;
        private BindingSpec<BackOffConfig> backoffConfigSpec = literally(BackOffConfig.builder()
                                                                                      .setInitialInterval(Duration.ofMillis(500))
                                                                                      .setMaxInterval(Duration.ofSeconds(1))
                                                                                      .setMaxElapsedTime(Duration.ofSeconds(5))
                                                                                      .build());

        public Builder setHost(BindingSpec<String> hostSpec) {
            this.hostSpec = checkNotNull(hostSpec);
            return this;
        }

        public Builder withBackoffConfig(BindingSpec<BackOffConfig> backoffConfigSpec) {
            this.backoffConfigSpec = checkNotNull(backoffConfigSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<ShellyPlug> build() {
            return new ShellyPlugModule(hostSpec, backoffConfigSpec, specifiedAnnotation());
        }
    }
}
