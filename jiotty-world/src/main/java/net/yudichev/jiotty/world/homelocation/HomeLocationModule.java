package net.yudichev.jiotty.world.homelocation;

import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;

public final class HomeLocationModule extends BaseLifecycleComponentModule implements ExposedKeyModule<HomeLocationService> {
    @Override
    protected void configure() {
        bind(getExposedKey()).to(registerLifecycleComponent(HomeLocationServiceImpl.class));
        expose(getExposedKey());
    }
}
