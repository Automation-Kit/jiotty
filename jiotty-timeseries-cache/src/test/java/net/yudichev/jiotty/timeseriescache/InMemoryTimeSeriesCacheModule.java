package net.yudichev.jiotty.timeseriescache;

import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

/// Installs an [InMemoryTimeSeriesCache] and exposes it as [TimeSeriesCache]. Suitable for deployments that don't need cross-process persistence — data is lost
/// on restart. For production deployments with persistence requirements, use [TimeSeriesCacheModule] (Postgres-backed) instead.
public final class InMemoryTimeSeriesCacheModule extends BaseExposedKeyModule<TimeSeriesCache> {
    private InMemoryTimeSeriesCacheModule(SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        bind(exposedKey).toInstance(new InMemoryTimeSeriesCache());
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<TimeSeriesCache, Builder> {
        @Override
        public ExposedKeyModule<TimeSeriesCache> build() {
            return new InMemoryTimeSeriesCacheModule(specifiedAnnotation());
        }
    }
}
