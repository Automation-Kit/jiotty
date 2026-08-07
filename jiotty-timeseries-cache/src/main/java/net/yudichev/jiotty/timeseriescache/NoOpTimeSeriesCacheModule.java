package net.yudichev.jiotty.timeseriescache;

import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import static net.yudichev.jiotty.common.inject.BaseModuleBuilder.simpleBuilder;

/// Installs a [NoOpTimeSeriesCache] and exposes it as [TimeSeriesCache]. Suitable for deployments that want the typed-stream API without retaining any data —
/// every read recomputes from source. For production deployments with persistence requirements use [TimeSeriesCacheModule] (Postgres-backed).
public final class NoOpTimeSeriesCacheModule extends BaseExposedKeyModule<TimeSeriesCache> {
    private NoOpTimeSeriesCacheModule(SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
    }

    public static BaseModuleBuilder<TimeSeriesCache, ?> builder() {
        return simpleBuilder(NoOpTimeSeriesCacheModule::new);
    }

    @Override
    protected void configure() {
        bind(exposedKey).toInstance(new NoOpTimeSeriesCache());
        expose(exposedKey);
    }
}
