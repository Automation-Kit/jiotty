package net.yudichev.jiotty.connector.brevo;

import jakarta.inject.Singleton;
import net.yudichev.jiotty.common.async.backoff.BackOffConfig;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.misc.LoggingUpstreamHealthHandler;
import net.yudichev.jiotty.common.misc.UpstreamHealthHandler;
import org.jspecify.annotations.Nullable;

import java.time.Duration;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.async.backoff.SharedUpstreamOutageBackOff.sharedOutageRetryExecutorModule;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

public final class BrevoClientModule extends BaseExposedKeyModule<BrevoClient> {
    /// Brevo's public API endpoint; tests and proxies override it via [Builder#withBaseUrl].
    private static final String DEFAULT_BASE_URL = "https://api.brevo.com";
    /// Bounds the whole call (connect, read, write). No user is waiting on this one — every caller is a background notification — so it is sized for a slow
    /// day rather than for interactive latency.
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    /// Longer than an interactive connector's window: nobody is blocked on the reply, and the messages this client carries (a deletion warning, a
    /// password-changed alert) are worth persisting with through a minutes-long blip rather than dropping. Still bounded, so a sustained outage reaches the
    /// health handler rather than retrying forever.
    private static final BackOffConfig RETRY_BACK_OFF = BackOffConfig.builder()
                                                                     .setInitialInterval(Duration.ofSeconds(2))
                                                                     .setMultiplier(2)
                                                                     .setMaxInterval(Duration.ofSeconds(30))
                                                                     .setMaxElapsedTime(Duration.ofMinutes(5))
                                                                     .build();

    private final BindingSpec<String> apiKeySpec;
    private final BindingSpec<String> baseUrlSpec;
    private final BindingSpec<Duration> timeoutSpec;
    private final BindingSpec<UpstreamHealthHandler> healthHandlerSpec;

    private BrevoClientModule(SpecifiedAnnotation specifiedAnnotation,
                              BindingSpec<String> apiKeySpec,
                              BindingSpec<String> baseUrlSpec,
                              BindingSpec<Duration> timeoutSpec,
                              BindingSpec<UpstreamHealthHandler> healthHandlerSpec) {
        super(specifiedAnnotation);
        this.apiKeySpec = checkNotNull(apiKeySpec, "setApiKey is required");
        this.baseUrlSpec = checkNotNull(baseUrlSpec);
        this.timeoutSpec = checkNotNull(timeoutSpec);
        this.healthHandlerSpec = checkNotNull(healthHandlerSpec);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        apiKeySpec.bind(String.class)
                  .annotatedWith(BrevoClientImpl.ApiKey.class)
                  .installedBy(this::installLifecycleComponentModule);
        baseUrlSpec.bind(String.class)
                   .annotatedWith(BrevoClientImpl.BaseUrl.class)
                   .installedBy(this::installLifecycleComponentModule);
        timeoutSpec.bind(Duration.class)
                   .annotatedWith(BrevoClientImpl.Timeout.class)
                   .installedBy(this::installLifecycleComponentModule);

        // Singleton: health is per upstream, so however many components consume this binding, they report into one handler.
        healthHandlerSpec.bind(UpstreamHealthHandler.class)
                         .annotatedWith(BrevoClientImpl.Dependency.class)
                         .in(Singleton.class)
                         .installedBy(this::installLifecycleComponentModule);

        installLifecycleComponentModule(sharedOutageRetryExecutorModule("brevo-retry",
                                                                        BrevoClientImpl.Dependency.class,
                                                                        RETRY_BACK_OFF));
        bind(exposedKey).to(registerLifecycleComponent(BrevoClientImpl.class));
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<BrevoClient, Builder> {
        /// The one spec with no default: a Brevo client without a key has nothing to be, so [#build()] rejects it rather than binding something unusable.
        private @Nullable BindingSpec<String> apiKeySpec;
        private BindingSpec<String> baseUrlSpec = literally(DEFAULT_BASE_URL);
        private BindingSpec<Duration> timeoutSpec = literally(DEFAULT_TIMEOUT);
        private BindingSpec<UpstreamHealthHandler> healthHandlerSpec = literally(new LoggingUpstreamHealthHandler("Brevo API"));

        public Builder setApiKey(BindingSpec<String> apiKeySpec) {
            this.apiKeySpec = checkNotNull(apiKeySpec);
            return this;
        }

        /// Overrides the API endpoint, which otherwise points at Brevo's public one.
        public Builder withBaseUrl(BindingSpec<String> baseUrlSpec) {
            this.baseUrlSpec = checkNotNull(baseUrlSpec);
            return this;
        }

        /// Overrides the connect/read/write/call timeout applied to every request.
        public Builder withTimeout(BindingSpec<Duration> timeoutSpec) {
            this.timeoutSpec = checkNotNull(timeoutSpec);
            return this;
        }

        /// Supplies the handler notified when Brevo calls start failing and when they recover; defaults to a [LoggingUpstreamHealthHandler].
        public Builder withHealthHandler(BindingSpec<UpstreamHealthHandler> healthHandlerSpec) {
            this.healthHandlerSpec = checkNotNull(healthHandlerSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<BrevoClient> build() {
            return new BrevoClientModule(specifiedAnnotation(), apiKeySpec, baseUrlSpec, timeoutSpec, healthHandlerSpec);
        }
    }
}
