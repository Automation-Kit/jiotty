package net.yudichev.jiotty.connector.expopush;

import com.google.common.reflect.TypeToken;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.lang.TypedBuilder;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

public final class ExpoPushSenderModule extends BaseLifecycleComponentModule implements ExposedKeyModule<ExpoPushSender> {
    private final BindingSpec<ExpoPushEventListener> eventListenerSpec;
    private final BindingSpec<Optional<String>> accessTokenSpec;
    private final BindingSpec<String> baseUrlSpec;

    private ExpoPushSenderModule(BindingSpec<ExpoPushEventListener> eventListenerSpec,
                                 BindingSpec<Optional<String>> accessTokenSpec,
                                 BindingSpec<String> baseUrlSpec) {
        this.eventListenerSpec = checkNotNull(eventListenerSpec);
        this.accessTokenSpec = checkNotNull(accessTokenSpec);
        this.baseUrlSpec = checkNotNull(baseUrlSpec);
    }

    @Override
    protected void configure() {
        eventListenerSpec.bind(ExpoPushEventListener.class).installedBy(this::installLifecycleComponentModule);
        accessTokenSpec.bind(new TypeLiteral<>() {})
                       .annotatedWith(ExpoPushSenderImpl.AccessToken.class)
                       .installedBy(this::installLifecycleComponentModule);
        baseUrlSpec.bind(String.class)
                   .annotatedWith(ExpoPushSenderImpl.BaseUrl.class)
                   .installedBy(this::installLifecycleComponentModule);
        bind(getExposedKey()).to(registerLifecycleComponent(ExpoPushSenderImpl.class));
        expose(getExposedKey());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder implements TypedBuilder<ExposedKeyModule<ExpoPushSender>> {
        private BindingSpec<ExpoPushEventListener> eventListenerSpec;
        private BindingSpec<Optional<String>> accessTokenSpec = literally(Optional.empty());
        private BindingSpec<String> baseUrlSpec = literally(ExpoPushSenderImpl.DEFAULT_BASE_URL);

        public Builder setEventListener(BindingSpec<ExpoPushEventListener> eventListenerSpec) {
            this.eventListenerSpec = checkNotNull(eventListenerSpec);
            return this;
        }

        public Builder withAccessToken(BindingSpec<String> accessTokenSpec) {
            this.accessTokenSpec = accessTokenSpec.map(TypeToken.of(String.class),
                                                       new TypeToken<>() {},
                                                       Optional::of);
            return this;
        }

        public Builder withBaseUrl(BindingSpec<String> baseUrlSpec) {
            this.baseUrlSpec = checkNotNull(baseUrlSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<ExpoPushSender> build() {
            return new ExpoPushSenderModule(eventListenerSpec, accessTokenSpec, baseUrlSpec);
        }
    }
}
