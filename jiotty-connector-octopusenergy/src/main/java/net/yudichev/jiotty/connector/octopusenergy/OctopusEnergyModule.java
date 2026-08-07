package net.yudichev.jiotty.connector.octopusenergy;

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

public final class OctopusEnergyModule extends BaseExposedKeyModule<OctopusEnergy> {
    private final BindingSpec<UpstreamHealthHandler> healthHandlerSpec;

    private OctopusEnergyModule(SpecifiedAnnotation specifiedAnnotation, BindingSpec<UpstreamHealthHandler> healthHandlerSpec) {
        super(specifiedAnnotation);
        this.healthHandlerSpec = checkNotNull(healthHandlerSpec);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        // Singleton: health is per upstream, so however many components consume this binding, they report into one handler.
        healthHandlerSpec.bind(UpstreamHealthHandler.class)
                         .annotatedWith(OctopusEnergyImpl.Dependency.class)
                         .in(Singleton.class)
                         .installedBy(this::installLifecycleComponentModule);
        installLifecycleComponentModule(sharedOutageRetryExecutorModule("octopus-api-retry",
                                                                        OctopusEnergyImpl.Dependency.class,
                                                                        BackOffConfig.builder()
                                                                                     .setInitialInterval(Duration.ofSeconds(1))
                                                                                     .setMultiplier(2)
                                                                                     .setMaxInterval(Duration.ofMinutes(1))
                                                                                     .build()));
        bind(exposedKey).to(registerLifecycleComponent(OctopusEnergyImpl.class));
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<OctopusEnergy, Builder> {
        private BindingSpec<UpstreamHealthHandler> healthHandlerSpec = literally(new LoggingUpstreamHealthHandler("Octopus API"));

        /// Supplies the handler notified when Octopus API calls start failing and when they recover; defaults to a [LoggingUpstreamHealthHandler].
        public Builder withHealthHandler(BindingSpec<UpstreamHealthHandler> healthHandlerSpec) {
            this.healthHandlerSpec = checkNotNull(healthHandlerSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<OctopusEnergy> build() {
            return new OctopusEnergyModule(specifiedAnnotation(), healthHandlerSpec);
        }
    }
}
