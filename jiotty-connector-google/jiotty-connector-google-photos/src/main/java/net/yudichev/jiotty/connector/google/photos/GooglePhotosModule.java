package net.yudichev.jiotty.connector.google.photos;

import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.connector.google.common.GoogleAuthorization;
import net.yudichev.jiotty.connector.google.common.impl.BaseGoogleServiceModule;

public final class GooglePhotosModule extends BaseGoogleServiceModule<GooglePhotosClient> {
    private GooglePhotosModule(BindingSpec<GoogleAuthorization> googleAuthorizationSpec, SpecifiedAnnotation specifiedAnnotation) {
        super(googleAuthorizationSpec, specifiedAnnotation);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void doConfigure() {
        bind(exposedKey).to(registerLifecycleComponent(GooglePhotosClientImpl.class));
        expose(exposedKey);
    }

    public static final class Builder extends BaseBuilder<GooglePhotosClient, Builder> {
        @Override
        public ExposedKeyModule<GooglePhotosClient> build() {
            return new GooglePhotosModule(getAuthorizationSpec(), specifiedAnnotation());
        }

        @Override
        protected Builder thisBuilder() {
            return this;
        }
    }
}
