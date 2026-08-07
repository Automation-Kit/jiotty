package net.yudichev.jiotty.connector.google.common.impl;

import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.connector.google.common.GoogleAuthorization;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.boundTo;
import static net.yudichev.jiotty.connector.google.common.impl.Bindings.Authorization;

public abstract class BaseGoogleServiceModule<T> extends BaseExposedKeyModule<T> {

    private final BindingSpec<GoogleAuthorization> googleAuthorizationSpec;

    protected BaseGoogleServiceModule(BindingSpec<GoogleAuthorization> googleAuthorizationSpec, SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.googleAuthorizationSpec = checkNotNull(googleAuthorizationSpec);
    }

    @Override
    protected final void configure() {
        googleAuthorizationSpec.bind(GoogleAuthorization.class)
                               .annotatedWith(Authorization.class)
                               .installedBy(this::installLifecycleComponentModule);
        doConfigure();
    }

    protected abstract void doConfigure();

    public abstract static class BaseBuilder<T, B extends BaseBuilder<T, B>> extends BaseModuleBuilder<T, B> {
        private BindingSpec<GoogleAuthorization> authorizationSpec = boundTo(GoogleAuthorization.class);

        protected BindingSpec<GoogleAuthorization> getAuthorizationSpec() {
            return authorizationSpec;
        }

        public B withAuthorization(BindingSpec<GoogleAuthorization> authorizationSpec) {
            this.authorizationSpec = checkNotNull(authorizationSpec);
            return thisBuilder();
        }

        protected abstract B thisBuilder();
    }
}
