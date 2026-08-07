package net.yudichev.jiotty.adminalerts;

import com.google.inject.Singleton;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import static net.yudichev.jiotty.common.inject.BaseModuleBuilder.simpleBuilder;

/// Installs a [LoggingAdminAlertService] and exposes it as [AdminAlertService]. For deployments that want operator-visible failure signalling without a
/// persistent store or alerting UI — use the Postgres-backed module for full bundling, history and operator-driven resolution.
public final class LoggingAdminAlertServiceModule extends BaseExposedKeyModule<AdminAlertService> {
    private LoggingAdminAlertServiceModule(SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
    }

    public static BaseModuleBuilder<AdminAlertService, ?> builder() {
        return simpleBuilder(LoggingAdminAlertServiceModule::new);
    }

    @Override
    protected void configure() {
        bind(exposedKey).to(LoggingAdminAlertService.class).in(Singleton.class);
        expose(exposedKey);
    }
}
