package net.yudichev.jiotty.timeseriescache;

import com.google.inject.Key;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

/// Installs a [NoOpTimeSeriesCache] and exposes it as [TimeSeriesCache]. Suitable for deployments that want the typed-stream API without retaining any data —
/// every read recomputes from source. For production deployments with persistence requirements use [TimeSeriesCacheModule] (Postgres-backed).
public final class NoOpTimeSeriesCacheModule extends BaseLifecycleComponentModule implements ExposedKeyModule<TimeSeriesCache> {
    private final Key<TimeSeriesCache> exposedKey;

    private NoOpTimeSeriesCacheModule(SpecifiedAnnotation specifiedAnnotation) {
        exposedKey = specifiedAnnotation.specify(ExposedKeyModule.super.getExposedKey().getTypeLiteral());
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Key<TimeSeriesCache> getExposedKey() {
        return exposedKey;
    }

    @Override
    protected void configure() {
        bind(exposedKey).toInstance(new NoOpTimeSeriesCache());
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<TimeSeriesCache, Builder> {
        @Override
        public ExposedKeyModule<TimeSeriesCache> build() {
            return new NoOpTimeSeriesCacheModule(specifiedAnnotation());
        }
    }
}
