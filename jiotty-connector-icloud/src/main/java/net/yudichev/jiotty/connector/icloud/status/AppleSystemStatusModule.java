package net.yudichev.jiotty.connector.icloud.status;

import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.async.backoff.BackOffConfig;
import net.yudichev.jiotty.common.async.backoff.BackingOffExceptionHandler;
import net.yudichev.jiotty.common.async.backoff.BackingOffExceptionHandlerModule;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutorModule;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import java.time.Duration;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Predicates.alwaysTrue;
import static net.yudichev.jiotty.common.inject.BindingSpec.exposedBy;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;

/// Exposes an [AppleSystemStatusSource] that reads Apple's public system status page, repeating a failed exchange with backoff on a thread of its own.
/// The builder varies the timeout of each request and the backoff.
public final class AppleSystemStatusModule extends BaseExposedKeyModule<AppleSystemStatusSource> {
    private final BindingSpec<Duration> timeoutSpec;
    private final BindingSpec<BackOffConfig> retryBackOffConfigSpec;

    private AppleSystemStatusModule(BindingSpec<Duration> timeoutSpec,
                                    BindingSpec<BackOffConfig> retryBackOffConfigSpec,
                                    SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.timeoutSpec = checkNotNull(timeoutSpec);
        this.retryBackOffConfigSpec = checkNotNull(retryBackOffConfigSpec);
    }

    @Override
    protected void configure() {
        timeoutSpec.bind(Duration.class).annotatedWith(AppleSystemStatusSourceImpl.Timeout.class).installedBy(this::installLifecycleComponentModule);
        // Every failure is retried: the retried operation is the exchange alone, and AppleSystemStatusSourceImpl parses the response after the retries.
        ExposedKeyModule<BackingOffExceptionHandler> retryHandlerModule =
                BackingOffExceptionHandlerModule.builder()
                                                .setRetryableExceptionPredicate(literally(alwaysTrue()))
                                                .withConfig(retryBackOffConfigSpec)
                                                .build();
        ExecutorProviderModule retryExecutorModule = ExecutorProviderModule.builder()
                                                                           .setThreadName(literally("apple-system-status-retry"))
                                                                           .withAnnotation(forAnnotation(uniqueAnnotation()))
                                                                           .build();
        installLifecycleComponentModule(RetryableOperationExecutorModule.builder()
                                                                        .setBackingOffExceptionHandler(exposedBy(retryHandlerModule))
                                                                        .withExecutor(exposedBy(retryExecutorModule))
                                                                        .withAnnotation(forAnnotation(AppleSystemStatusSourceImpl.Dependency.class))
                                                                        .build());

        bind(exposedKey).to(registerLifecycleComponent(AppleSystemStatusSourceImpl.class));
        expose(exposedKey);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends BaseModuleBuilder<AppleSystemStatusSource, Builder> {
        private BindingSpec<Duration> timeoutSpec = literally(Duration.ofSeconds(30));
        private BindingSpec<BackOffConfig> retryBackOffConfigSpec = literally(BackOffConfig.builder()
                                                                                           .setInitialInterval(Duration.ofSeconds(5))
                                                                                           .setMaxInterval(Duration.ofSeconds(12))
                                                                                           .setMaxElapsedTime(Duration.ofMinutes(2))
                                                                                           .build());

        /// @param timeoutSpec the connect, read, write and whole-call timeout applied to every request; defaults to 30 seconds
        public Builder withTimeout(BindingSpec<Duration> timeoutSpec) {
            this.timeoutSpec = checkNotNull(timeoutSpec);
            return this;
        }

        /// @param retryBackOffConfigSpec the backoff applied after a failed exchange, whose maximum elapsed time bounds the attempts of one read; defaults
        ///                               to an initial interval of 5 seconds, a maximum interval of 12 seconds and a maximum elapsed time of 2 minutes
        public Builder withRetryBackOffConfig(BindingSpec<BackOffConfig> retryBackOffConfigSpec) {
            this.retryBackOffConfigSpec = checkNotNull(retryBackOffConfigSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<AppleSystemStatusSource> build() {
            return new AppleSystemStatusModule(timeoutSpec, retryBackOffConfigSpec, specifiedAnnotation());
        }
    }
}
