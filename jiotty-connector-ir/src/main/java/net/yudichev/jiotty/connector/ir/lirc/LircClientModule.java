package net.yudichev.jiotty.connector.ir.lirc;

import net.yudichev.jiotty.common.async.backoff.BackOffConfig;
import net.yudichev.jiotty.common.async.backoff.BackingOffExceptionHandlerModule;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutorModule;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import java.lang.annotation.Annotation;
import java.time.Duration;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.boundTo;
import static net.yudichev.jiotty.common.inject.BindingSpec.exposedBy;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;

public final class LircClientModule extends BaseExposedKeyModule<LircClient> {
    private final BindingSpec<String> addressSpec;
    private final BindingSpec<Integer> portSpec;
    private final BindingSpec<Duration> timeoutSpec;
    private final BindingSpec<BackOffConfig> heartbeatRetryBackoffConfigSpec;
    private final BindingSpec<BackOffConfig> commandBackoffConfigSpec;

    private LircClientModule(BindingSpec<String> addressSpec,
                             BindingSpec<Integer> portSpec,
                             BindingSpec<Duration> timeoutSpec,
                             BindingSpec<BackOffConfig> heartbeatRetryBackoffConfigSpec,
                             BindingSpec<BackOffConfig> commandBackoffConfigSpec,
                             SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.addressSpec = checkNotNull(addressSpec);
        this.portSpec = checkNotNull(portSpec);
        this.timeoutSpec = checkNotNull(timeoutSpec);
        this.heartbeatRetryBackoffConfigSpec = checkNotNull(heartbeatRetryBackoffConfigSpec);
        this.commandBackoffConfigSpec = checkNotNull(commandBackoffConfigSpec);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        addressSpec.bind(String.class)
                   .annotatedWith(TcpLircClient.Address.class)
                   .installedBy(this::installLifecycleComponentModule);
        portSpec.bind(Integer.class)
                .annotatedWith(TcpLircClient.Port.class)
                .installedBy(this::installLifecycleComponentModule);
        timeoutSpec.bind(Duration.class)
                   .annotatedWith(TcpLircClient.Timeout.class)
                   .installedBy(this::installLifecycleComponentModule);
        installLifecycleComponentModule(createRetryableOperationExecutorModule(TcpLircClient.Heartbeat.class, heartbeatRetryBackoffConfigSpec));
        installLifecycleComponentModule(createRetryableOperationExecutorModule(TcpLircClient.Command.class, commandBackoffConfigSpec));
        bind(exposedKey).to(registerLifecycleComponent(TcpLircClient.class));
        expose(exposedKey);
    }

    private static ExposedKeyModule<RetryableOperationExecutor> createRetryableOperationExecutorModule(Class<? extends Annotation> annotation,
                                                                                                       BindingSpec<BackOffConfig> backoffSpec) {
        return RetryableOperationExecutorModule
                .builder()
                .setBackingOffExceptionHandler(
                        exposedBy(BackingOffExceptionHandlerModule.builder()
                                                                  .setRetryableExceptionPredicate(boundTo(LircRetryableExceptionPredicate.class))
                                                                  .withConfig(backoffSpec)
                                                                  .build()))
                .withAnnotation(forAnnotation(annotation))
                .build();
    }

    public static final class Builder extends BaseModuleBuilder<LircClient, Builder> {
        private BindingSpec<String> addressSpec = literally("127.0.0.1");
        private BindingSpec<Integer> portSpec = literally(8765);
        private BindingSpec<Duration> timeoutSpec = literally(Duration.ofSeconds(5));
        private BindingSpec<BackOffConfig> heartbeatRetryBackoffConfigSpec =
                literally(BackOffConfig.builder()
                                       .setInitialInterval(Duration.ofMillis(10))
                                       .setMaxInterval(Duration.ofMinutes(1))
                                       .setMaxElapsedTime(Duration.ofDays(1))
                                       .build());
        private BindingSpec<BackOffConfig> commandBackoffConfigSpec =
                literally(BackOffConfig.builder()
                                       .setInitialInterval(Duration.ofMillis(10))
                                       .setMaxInterval(Duration.ofSeconds(15))
                                       .setMaxElapsedTime(Duration.ofMinutes(5))
                                       .build());

        private Builder() {
        }

        public Builder withAddress(BindingSpec<String> addressSpec) {
            this.addressSpec = checkNotNull(addressSpec);
            return this;
        }

        public Builder withPort(BindingSpec<Integer> portSpec) {
            this.portSpec = portSpec;
            return this;
        }

        public Builder withTimeout(BindingSpec<Duration> timeoutSpec) {
            this.timeoutSpec = checkNotNull(timeoutSpec);
            return this;
        }

        public Builder withHeartbeatRetryBackoffConfig(BindingSpec<BackOffConfig> heartbeatRetryBackoffConfigSpec) {
            this.heartbeatRetryBackoffConfigSpec = checkNotNull(heartbeatRetryBackoffConfigSpec);
            return this;
        }

        public Builder withCommandRetryBackoffConfig(BindingSpec<BackOffConfig> commandBackoffConfigSpec) {
            this.commandBackoffConfigSpec = checkNotNull(commandBackoffConfigSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<LircClient> build() {
            return new LircClientModule(addressSpec, portSpec, timeoutSpec, heartbeatRetryBackoffConfigSpec, commandBackoffConfigSpec, specifiedAnnotation());
        }
    }
}
