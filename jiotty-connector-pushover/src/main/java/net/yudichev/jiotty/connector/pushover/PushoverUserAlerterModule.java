package net.yudichev.jiotty.connector.pushover;

import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import static com.google.common.base.Preconditions.checkNotNull;

public final class PushoverUserAlerterModule extends BaseExposedKeyModule<UserAlerter> {
    private final BindingSpec<String> apiTokenSpec;

    private PushoverUserAlerterModule(BindingSpec<String> apiTokenSpec, SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.apiTokenSpec = checkNotNull(apiTokenSpec);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        apiTokenSpec.bind(String.class).annotatedWith(PushoverUserAlerter.ApiToken.class).installedBy(this::installLifecycleComponentModule);
        bind(exposedKey).to(registerLifecycleComponent(PushoverUserAlerter.class));
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<UserAlerter, Builder> {
        private BindingSpec<String> apiTokenSpec;

        public Builder setApiToken(BindingSpec<String> apiTokenSpec) {
            this.apiTokenSpec = checkNotNull(apiTokenSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<UserAlerter> build() {
            return new PushoverUserAlerterModule(apiTokenSpec, specifiedAnnotation());
        }
    }
}
