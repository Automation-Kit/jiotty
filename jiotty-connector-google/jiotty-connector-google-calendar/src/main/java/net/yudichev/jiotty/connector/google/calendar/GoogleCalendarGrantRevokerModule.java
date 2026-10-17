package net.yudichev.jiotty.connector.google.calendar;

import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.security.StoredGrantRevoker;
import net.yudichev.jiotty.security.StoredGrantRevokerModule;

import static net.yudichev.jiotty.common.inject.BaseModuleBuilder.simpleBuilder;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;

/// Publishes a [StoredGrantRevoker] for the tokens [GoogleCalendarModule] stores, whatever client id they were obtained under, so one instance serves every
/// var store such a module writes to.
public final class GoogleCalendarGrantRevokerModule extends BaseExposedKeyModule<StoredGrantRevoker> {
    private GoogleCalendarGrantRevokerModule(SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
    }

    public static BaseModuleBuilder<StoredGrantRevoker, ?> builder() {
        return simpleBuilder(GoogleCalendarGrantRevokerModule::new);
    }

    @Override
    protected void configure() {
        ExposedKeyModule<StoredGrantRevoker> revokerModule = StoredGrantRevokerModule.builder()
                                                                                     .setApiName(literally(GoogleCalendarModule.BASE_API_NAME))
                                                                                     .setScope(literally(GoogleCalendarScopes.CALENDAR_READONLY))
                                                                                     .withRevocationUrl(literally(GoogleCalendarModule.REVOCATION_URL))
                                                                                     .withAnnotation(forAnnotation(uniqueAnnotation()))
                                                                                     .build();
        installLifecycleComponentModule(revokerModule);
        bind(exposedKey).to(revokerModule.getExposedKey());
        expose(exposedKey);
    }
}
