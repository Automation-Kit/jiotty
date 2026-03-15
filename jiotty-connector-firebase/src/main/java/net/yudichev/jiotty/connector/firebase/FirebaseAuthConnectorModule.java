package net.yudichev.jiotty.connector.firebase;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.reflect.TypeToken;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.lang.TypedBuilder;

import java.time.Duration;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

public final class FirebaseAuthConnectorModule extends BaseLifecycleComponentModule implements ExposedKeyModule<FirebaseAuthConnector> {
    private final BindingSpec<Optional<GoogleCredentials>> credentialsSpec;
    private final BindingSpec<Optional<String>> projectIdSpec;
    private final BindingSpec<Duration> httpTimeoutSpec;

    private FirebaseAuthConnectorModule(BindingSpec<Optional<GoogleCredentials>> credentialsSpec,
                                        BindingSpec<Optional<String>> projectIdSpec,
                                        BindingSpec<Duration> httpTimeoutSpec) {
        this.credentialsSpec = checkNotNull(credentialsSpec, "credentialsSpec");
        this.projectIdSpec = checkNotNull(projectIdSpec, "projectIdSpec");
        this.httpTimeoutSpec = checkNotNull(httpTimeoutSpec, "httpTimeoutSpec");
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        credentialsSpec.bind(new TypeLiteral<>() {})
                       .annotatedWith(FirebaseAuthConnectorImpl.Dependency.class)
                       .installedBy(this::installLifecycleComponentModule);
        projectIdSpec.bind(new TypeLiteral<>() {})
                     .annotatedWith(FirebaseAuthConnectorImpl.Dependency.class)
                     .installedBy(this::installLifecycleComponentModule);
        httpTimeoutSpec.bind(Duration.class)
                       .annotatedWith(FirebaseAuthConnectorImpl.Dependency.class)
                       .installedBy(this::installLifecycleComponentModule);
        bind(getExposedKey()).to(registerLifecycleComponent(FirebaseAuthConnectorImpl.class));
        expose(getExposedKey());
    }

    public static final class Builder implements TypedBuilder<ExposedKeyModule<FirebaseAuthConnector>> {
        private BindingSpec<Optional<GoogleCredentials>> credentialsSpec = literally(Optional.empty());
        private BindingSpec<Optional<String>> projectIdSpec = literally(Optional.empty());
        private BindingSpec<Duration> httpTimeoutSpec = literally(Duration.ofSeconds(10));

        public Builder withCredentials(BindingSpec<GoogleCredentials> credentialsSpec) {
            this.credentialsSpec = checkNotNull(credentialsSpec, "credentialsSpec").map(new TypeToken<>() {}, new TypeToken<>() {}, Optional::of);
            return this;
        }

        public Builder withProjectId(BindingSpec<String> projectIdSpec) {
            this.projectIdSpec = checkNotNull(projectIdSpec, "projectIdSpec").map(new TypeToken<>() {}, new TypeToken<>() {}, Optional::of);
            return this;
        }

        /// Configures the Firebase Admin SDK HTTP timeout.
        ///
        /// The same timeout value is applied to the SDK's connect, read, and write timeouts for each HTTP call. This is not an end-to-end timeout for
        /// [FirebaseAuthConnector#verifyUserToken(String)], which may perform multiple HTTP calls.
        ///
        public Builder withHttpTimeout(BindingSpec<Duration> httpTimeoutSpec) {
            this.httpTimeoutSpec = checkNotNull(httpTimeoutSpec, "httpTimeoutSpec");
            return this;
        }

        @Override
        public ExposedKeyModule<FirebaseAuthConnector> build() {
            return new FirebaseAuthConnectorModule(credentialsSpec, projectIdSpec, httpTimeoutSpec);
        }
    }
}
