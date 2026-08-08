package net.yudichev.jiotty.connector.anthropic;

import com.google.inject.Singleton;
import net.yudichev.jiotty.common.async.backoff.BackOffConfig;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.misc.LoggingUpstreamHealthHandler;
import net.yudichev.jiotty.common.misc.UpstreamHealthHandler;

import java.time.Duration;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.async.backoff.SharedUpstreamOutageBackOff.sharedOutageRetryExecutorModule;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

public final class AnthropicClientModule extends BaseExposedKeyModule<AnthropicClient> {
    /// Anthropic's public API endpoint; tests and proxies override it via [Builder#withBaseUrl].
    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    /// Generous enough for a long grounded answer on a slow day, short enough that a user waiting on a reply is not left hanging. Bounds the whole call
    /// (connect, read, write) — an unbounded default would pin the caller's async context indefinitely.
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    /// Kept deliberately short: unlike a background poller, the retry window here sits inside a request a user is waiting on. It rides out a single
    /// `overloaded_error` blip and then gives up, so the caller can say so rather than time out.
    private static final BackOffConfig RETRY_BACK_OFF = BackOffConfig.builder()
                                                                     .setInitialInterval(Duration.ofSeconds(1))
                                                                     .setMultiplier(2)
                                                                     .setMaxInterval(Duration.ofSeconds(5))
                                                                     .setMaxElapsedTime(Duration.ofSeconds(20))
                                                                     .build();

    private final BindingSpec<String> apiKeySpec;
    private final BindingSpec<String> baseUrlSpec;
    private final BindingSpec<Duration> timeoutSpec;
    private final BindingSpec<UpstreamHealthHandler> healthHandlerSpec;

    private AnthropicClientModule(SpecifiedAnnotation specifiedAnnotation,
                                  BindingSpec<String> apiKeySpec,
                                  BindingSpec<String> baseUrlSpec,
                                  BindingSpec<Duration> timeoutSpec,
                                  BindingSpec<UpstreamHealthHandler> healthHandlerSpec) {
        super(specifiedAnnotation);
        this.apiKeySpec = checkNotNull(apiKeySpec);
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
                  .annotatedWith(AnthropicClientImpl.ApiKey.class)
                  .installedBy(this::installLifecycleComponentModule);
        baseUrlSpec.bind(String.class)
                   .annotatedWith(AnthropicClientImpl.BaseUrl.class)
                   .installedBy(this::installLifecycleComponentModule);
        timeoutSpec.bind(Duration.class)
                   .annotatedWith(AnthropicClientImpl.Timeout.class)
                   .installedBy(this::installLifecycleComponentModule);

        // Singleton: health is per upstream, so however many components consume this binding, they report into one handler.
        healthHandlerSpec.bind(UpstreamHealthHandler.class)
                         .annotatedWith(AnthropicClientImpl.Dependency.class)
                         .in(Singleton.class)
                         .installedBy(this::installLifecycleComponentModule);

        installLifecycleComponentModule(sharedOutageRetryExecutorModule("anthropic-retry",
                                                                        AnthropicClientImpl.Dependency.class,
                                                                        RETRY_BACK_OFF));
        bind(exposedKey).to(registerLifecycleComponent(AnthropicClientImpl.class));
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<AnthropicClient, Builder> {
        private BindingSpec<String> apiKeySpec;
        private BindingSpec<String> baseUrlSpec = literally(DEFAULT_BASE_URL);
        private BindingSpec<Duration> timeoutSpec = literally(DEFAULT_TIMEOUT);
        private BindingSpec<UpstreamHealthHandler> healthHandlerSpec = literally(new LoggingUpstreamHealthHandler("Anthropic API"));

        public Builder setApiKey(BindingSpec<String> apiKeySpec) {
            this.apiKeySpec = checkNotNull(apiKeySpec);
            return this;
        }

        /// Overrides the API endpoint; defaults to [#DEFAULT_BASE_URL].
        public Builder withBaseUrl(BindingSpec<String> baseUrlSpec) {
            this.baseUrlSpec = checkNotNull(baseUrlSpec);
            return this;
        }

        /// Overrides the connect/read/write/call timeout applied to every request; defaults to [#DEFAULT_TIMEOUT].
        public Builder withTimeout(BindingSpec<Duration> timeoutSpec) {
            this.timeoutSpec = checkNotNull(timeoutSpec);
            return this;
        }

        /// Supplies the handler notified when Anthropic calls start failing and when they recover; defaults to a [LoggingUpstreamHealthHandler].
        public Builder withHealthHandler(BindingSpec<UpstreamHealthHandler> healthHandlerSpec) {
            this.healthHandlerSpec = checkNotNull(healthHandlerSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<AnthropicClient> build() {
            return new AnthropicClientModule(specifiedAnnotation(), apiKeySpec, baseUrlSpec, timeoutSpec, healthHandlerSpec);
        }
    }
}
