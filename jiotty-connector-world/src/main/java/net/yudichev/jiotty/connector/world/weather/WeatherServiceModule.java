package net.yudichev.jiotty.connector.world.weather;

import com.google.inject.Key;
import com.google.inject.Singleton;
import net.yudichev.jiotty.common.async.backoff.BackOffConfig;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.HasWithAnnotation;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.lang.TypedBuilder;
import net.yudichev.jiotty.common.misc.LoggingUpstreamHealthHandler;
import net.yudichev.jiotty.common.misc.UpstreamHealthHandler;

import java.time.Duration;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.async.backoff.SharedUpstreamOutageBackOff.sharedOutageRetryExecutorModule;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

public final class WeatherServiceModule extends BaseLifecycleComponentModule implements ExposedKeyModule<WeatherService> {
    private final BindingSpec<String> apiKeySpec;
    private final BindingSpec<UpstreamHealthHandler> healthHandlerSpec;
    private final Key<WeatherService> exposedKey;

    private WeatherServiceModule(SpecifiedAnnotation specifiedAnnotation,
                                 BindingSpec<String> apiKeySpec,
                                 BindingSpec<UpstreamHealthHandler> healthHandlerSpec) {
        exposedKey = specifiedAnnotation.specify(ExposedKeyModule.super.getExposedKey().getTypeLiteral());
        this.apiKeySpec = checkNotNull(apiKeySpec);
        this.healthHandlerSpec = checkNotNull(healthHandlerSpec);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Key<WeatherService> getExposedKey() {
        return exposedKey;
    }

    @Override
    protected void configure() {
        apiKeySpec.bind(String.class)
                  .annotatedWith(WeatherServiceImpl.ApiKey.class)
                  .installedBy(this::installLifecycleComponentModule);

        // Singleton: health is per upstream, so however many components consume this binding, they report into one handler.
        healthHandlerSpec.bind(UpstreamHealthHandler.class)
                         .annotatedWith(WeatherServiceImpl.Dependency.class)
                         .in(Singleton.class)
                         .installedBy(this::installLifecycleComponentModule);

        installLifecycleComponentModule(sharedOutageRetryExecutorModule("weather-api-retry",
                                                                        WeatherServiceImpl.Dependency.class,
                                                                        BackOffConfig.builder()
                                                                                     .setInitialInterval(Duration.ofSeconds(1))
                                                                                     .setMultiplier(2)
                                                                                     .setMaxInterval(Duration.ofMinutes(1))
                                                                                     .build()));
        bind(exposedKey).to(registerLifecycleComponent(WeatherServiceImpl.class));
        expose(exposedKey);
    }

    public static final class Builder implements TypedBuilder<ExposedKeyModule<WeatherService>>, HasWithAnnotation {
        private BindingSpec<String> apiKeySpec;
        private BindingSpec<UpstreamHealthHandler> healthHandlerSpec = literally(new LoggingUpstreamHealthHandler("weather API"));
        private SpecifiedAnnotation specifiedAnnotation = SpecifiedAnnotation.forNoAnnotation();

        public Builder setApiKey(BindingSpec<String> apiKeySpec) {
            this.apiKeySpec = checkNotNull(apiKeySpec);
            return this;
        }

        /// Supplies the handler notified when weather API calls start failing and when they recover; defaults to a [LoggingUpstreamHealthHandler].
        public Builder withHealthHandler(BindingSpec<UpstreamHealthHandler> healthHandlerSpec) {
            this.healthHandlerSpec = checkNotNull(healthHandlerSpec);
            return this;
        }

        @Override
        public Builder withAnnotation(SpecifiedAnnotation specifiedAnnotation) {
            this.specifiedAnnotation = checkNotNull(specifiedAnnotation);
            return this;
        }

        @Override
        public ExposedKeyModule<WeatherService> build() {
            return new WeatherServiceModule(specifiedAnnotation, apiKeySpec, healthHandlerSpec);
        }
    }
}
