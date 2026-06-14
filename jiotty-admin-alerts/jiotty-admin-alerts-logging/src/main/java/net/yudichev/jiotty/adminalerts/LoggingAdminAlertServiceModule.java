package net.yudichev.jiotty.adminalerts;

import com.google.inject.Key;
import com.google.inject.Singleton;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

/// Installs a [LoggingAdminAlertService] and exposes it as [AdminAlertService]. For deployments that want operator-visible failure signalling without a
/// persistent store or alerting UI — use the Postgres-backed module for full bundling, history and operator-driven resolution.
public final class LoggingAdminAlertServiceModule extends BaseLifecycleComponentModule implements ExposedKeyModule<AdminAlertService> {
    private final Key<AdminAlertService> exposedKey;

    private LoggingAdminAlertServiceModule(SpecifiedAnnotation specifiedAnnotation) {
        exposedKey = specifiedAnnotation.specify(ExposedKeyModule.super.getExposedKey().getTypeLiteral());
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Key<AdminAlertService> getExposedKey() {
        return exposedKey;
    }

    @Override
    protected void configure() {
        bind(exposedKey).to(LoggingAdminAlertService.class).in(Singleton.class);
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<AdminAlertService, Builder> {
        @Override
        public ExposedKeyModule<AdminAlertService> build() {
            return new LoggingAdminAlertServiceModule(specifiedAnnotation());
        }
    }
}
