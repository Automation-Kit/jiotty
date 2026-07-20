package net.yudichev.jiotty.connector.google.calendar;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.reflect.TypeToken;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.time.calendar.CalendarService;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import net.yudichev.jiotty.security.OAuth2TokenManagerModule;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.boundTo;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;

/// Exposes a [CalendarService] backed by the Google Calendar API. The service authenticates as an OAuth2 public client via PKCE: the UI obtains the auth code
/// and code verifier and supplies them via [Builder#withAuthCode]/[Builder#withCodeVerifier]. Token exchange, persistence and refresh are handled by the
/// embedded [OAuth2TokenManagerModule].
public final class GoogleCalendarModule extends BaseLifecycleComponentModule implements ExposedKeyModule<CalendarService> {
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String AUTHORIZATION_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String BASE_API_NAME = "GoogleCalendar";

    private final BindingSpec<String> clientIdSpec;
    private final BindingSpec<String> redirectUriSpec;
    private final BindingSpec<Optional<String>> authCodeSpec;
    private final BindingSpec<Optional<String>> codeVerifierSpec;
    private final BindingSpec<Duration> timeoutSpec;
    private final BindingSpec<String> logSubjectIdSpec;
    private final BindingSpec<VarStore> varStoreSpec;
    private final @Nullable BindingSpec<String> clientSecretSpec;
    private final boolean localLogin;
    private final Key<CalendarService> exposedKey;

    private GoogleCalendarModule(SpecifiedAnnotation specifiedAnnotation,
                                 BindingSpec<String> clientIdSpec,
                                 BindingSpec<String> redirectUriSpec,
                                 BindingSpec<Optional<String>> authCodeSpec,
                                 BindingSpec<Optional<String>> codeVerifierSpec,
                                 BindingSpec<Duration> timeoutSpec,
                                 BindingSpec<String> logSubjectIdSpec,
                                 BindingSpec<VarStore> varStoreSpec,
                                 @Nullable BindingSpec<String> clientSecretSpec,
                                 boolean localLogin) {
        exposedKey = specifiedAnnotation.specify(ExposedKeyModule.super.getExposedKey().getTypeLiteral());
        this.clientIdSpec = checkNotNull(clientIdSpec);
        this.redirectUriSpec = checkNotNull(redirectUriSpec);
        this.authCodeSpec = checkNotNull(authCodeSpec);
        this.codeVerifierSpec = checkNotNull(codeVerifierSpec);
        this.timeoutSpec = checkNotNull(timeoutSpec);
        this.logSubjectIdSpec = checkNotNull(logSubjectIdSpec);
        this.varStoreSpec = checkNotNull(varStoreSpec);
        this.clientSecretSpec = clientSecretSpec;
        this.localLogin = localLogin;
    }

    /// The API name (and its log/executor-name discriminator) for the embedded token manager: tags its logs with the supplied subject id so concurrent
    /// per-user instances stay distinguishable. Falls back to a bare {@value #BASE_API_NAME} when no subject id is supplied.
    @VisibleForTesting
    static String apiName(String logSubjectId) {
        return logSubjectId.isBlank() ? BASE_API_NAME : BASE_API_NAME + '-' + logSubjectId;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Key<CalendarService> getExposedKey() {
        return exposedKey;
    }

    @Override
    protected void configure() {
        // No client secret: this is a public client that authenticates via PKCE (code verifier supplied per auth-code exchange).
        var tokenManagerModuleBuilder = OAuth2TokenManagerModule.builder()
                                                                .setClientId(clientIdSpec)
                                                                .setApiName(logSubjectIdSpec.map(new TypeToken<>() {},
                                                                                                 new TypeToken<>() {},
                                                                                                 GoogleCalendarModule::apiName))
                                                                .setTokenUrl(literally(TOKEN_URL))
                                                                .setScope(literally(GoogleCalendarScopes.CALENDAR_READONLY))
                                                                .withVarStore(varStoreSpec)
                                                                .withAnnotation(forAnnotation(GoogleCalendarService.Dependency.class));
        if (localLogin) {
            // Enthusiast/manual mode: obtain the token via a local browser login (loopback redirect). access_type=offline + prompt=consent make Google return
            // a refresh token. Google requires a client secret on the token exchange for the "Desktop app" client this uses, supplied via withClientSecret.
            tokenManagerModuleBuilder.withLoginUrl(literally(AUTHORIZATION_URL))
                                     .withLoginExtraParams(literally(Map.of("access_type", "offline", "prompt", "consent")));
        }
        if (clientSecretSpec != null) {
            tokenManagerModuleBuilder.withClientSecret(clientSecretSpec);
        }
        installLifecycleComponentModule(tokenManagerModuleBuilder.build());
        logSubjectIdSpec.bind(String.class)
                        .annotatedWith(GoogleCalendarService.LogSubjectId.class)
                        .installedBy(this::installLifecycleComponentModule);
        redirectUriSpec.bind(String.class)
                       .annotatedWith(GoogleCalendarService.RedirectUri.class)
                       .installedBy(this::installLifecycleComponentModule);
        authCodeSpec.bind(new TypeLiteral<>() {})
                    .annotatedWith(GoogleCalendarService.AuthCode.class)
                    .installedBy(this::installLifecycleComponentModule);
        codeVerifierSpec.bind(new TypeLiteral<>() {})
                        .annotatedWith(GoogleCalendarService.CodeVerifier.class)
                        .installedBy(this::installLifecycleComponentModule);
        timeoutSpec.bind(Duration.class)
                   .annotatedWith(GoogleCalendarService.Timeout.class)
                   .installedBy(this::installLifecycleComponentModule);

        bind(exposedKey).to(registerLifecycleComponent(GoogleCalendarService.class));
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<CalendarService, Builder> {
        private BindingSpec<String> clientIdSpec;
        private BindingSpec<String> redirectUriSpec;
        private BindingSpec<Optional<String>> authCodeSpec = literally(Optional.empty());
        private BindingSpec<Optional<String>> codeVerifierSpec = literally(Optional.empty());
        private BindingSpec<Duration> timeoutSpec = literally(Duration.ofSeconds(30));
        private BindingSpec<String> logSubjectIdSpec = literally("");
        private BindingSpec<VarStore> varStoreSpec = boundTo(VarStore.class);
        private @Nullable BindingSpec<String> clientSecretSpec;
        private boolean localLogin;

        public Builder setClientId(BindingSpec<String> clientIdSpec) {
            this.clientIdSpec = checkNotNull(clientIdSpec);
            return this;
        }

        public Builder setRedirectUri(BindingSpec<String> redirectUriSpec) {
            this.redirectUriSpec = checkNotNull(redirectUriSpec);
            return this;
        }

        /// The OAuth2 authorization code freshly obtained by the UI, or [Optional#empty()] when starting from a persisted token (e.g. after a restart).
        public Builder withAuthCode(BindingSpec<Optional<String>> authCodeSpec) {
            this.authCodeSpec = checkNotNull(authCodeSpec);
            return this;
        }

        /// The PKCE code verifier that the UI used to obtain the authorization code; required whenever [#withAuthCode] is non-empty.
        public Builder withCodeVerifier(BindingSpec<Optional<String>> codeVerifierSpec) {
            this.codeVerifierSpec = checkNotNull(codeVerifierSpec);
            return this;
        }

        /// The connect/read timeout applied to every Google Calendar API call; defaults to 30 seconds.
        public Builder withTimeout(BindingSpec<Duration> timeoutSpec) {
            this.timeoutSpec = checkNotNull(timeoutSpec);
            return this;
        }

        /// A GDPR-safe subject id (e.g. the internal user id) used to tag this instance's log lines — via its executor thread name and the embedded token
        /// manager's name — so concurrent per-user instances stay distinguishable in a shared log. Defaults to empty (single-instance use).
        public Builder withLogSubjectId(BindingSpec<String> logSubjectIdSpec) {
            this.logSubjectIdSpec = checkNotNull(logSubjectIdSpec);
            return this;
        }

        public Builder withVarStore(BindingSpec<VarStore> varStoreSpec) {
            this.varStoreSpec = checkNotNull(varStoreSpec);
            return this;
        }

        /// Obtains the OAuth token via a local browser login (loopback redirect) instead of a UI-supplied auth code — for enthusiast/local single-user use and
        /// the manual runner. The client must be a Google OAuth client that permits a loopback redirect (a "Desktop app" client). Defaults to off.
        public Builder withLocalLogin(boolean localLogin) {
            this.localLogin = localLogin;
            return this;
        }

        /// Sets the OAuth client secret. The production mobile client is a public PKCE client and omits this; it is needed only for the loopback "Desktop app"
        /// client used with [#withLocalLogin], because Google requires a secret on that client's token exchange.
        public Builder withClientSecret(BindingSpec<String> clientSecretSpec) {
            this.clientSecretSpec = checkNotNull(clientSecretSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<CalendarService> build() {
            return new GoogleCalendarModule(specifiedAnnotation(),
                                            clientIdSpec,
                                            redirectUriSpec,
                                            authCodeSpec,
                                            codeVerifierSpec,
                                            timeoutSpec,
                                            logSubjectIdSpec,
                                            varStoreSpec,
                                            clientSecretSpec,
                                            localLogin);
        }
    }
}
