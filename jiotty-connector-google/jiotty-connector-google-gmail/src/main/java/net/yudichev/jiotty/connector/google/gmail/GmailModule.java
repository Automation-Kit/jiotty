package net.yudichev.jiotty.connector.google.gmail;

import com.google.api.services.gmail.Gmail;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import jakarta.inject.Singleton;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.connector.google.common.GoogleAuthorization;
import net.yudichev.jiotty.connector.google.common.impl.BaseGoogleServiceModule;

public final class GmailModule extends BaseGoogleServiceModule<GmailClient> {
    private GmailModule(BindingSpec<GoogleAuthorization> authorizationSpec, SpecifiedAnnotation specifiedAnnotation) {
        super(authorizationSpec, specifiedAnnotation);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void doConfigure() {
        install(new FactoryModuleBuilder()
                        .implement(GmailMessage.class, InternalGmailMessage.class)
                        .implement(GmailMessageAttachment.class, InternalGmailMessageAttachment.class)
                        .build(InternalGmailObjectFactory.class));

        bind(Gmail.class).annotatedWith(Bindings.GmailService.class).toProvider(GmailProvider.class).in(Singleton.class);
        bind(exposedKey).to(registerLifecycleComponent(GmailClientImpl.class));
        expose(exposedKey);
    }

    public static final class Builder extends BaseBuilder<GmailClient, Builder> {
        @Override
        public ExposedKeyModule<GmailClient> build() {
            return new GmailModule(getAuthorizationSpec(), specifiedAnnotation());
        }

        @Override
        protected Builder thisBuilder() {
            return this;
        }
    }
}
