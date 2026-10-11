package net.yudichev.jiotty.security;

import com.google.inject.Module;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.inject.BindingSpec;

import java.util.Optional;
import java.util.function.Consumer;

import static net.yudichev.jiotty.security.Bindings.ApiName;
import static net.yudichev.jiotty.security.Bindings.ClientID;
import static net.yudichev.jiotty.security.Bindings.ClientSecret;
import static net.yudichev.jiotty.security.Bindings.RevocationUrl;
import static net.yudichev.jiotty.security.Bindings.Scope;
import static net.yudichev.jiotty.security.Bindings.TokenUrl;

/// Binds what identifies one OAuth2 client of one API, which [OAuth2TokenManagerImpl] and [StoredGrantRevokerImpl] both read — so the two key a stored token
/// alike and reach the same endpoints.
final class OAuth2ClientBindings {
    private OAuth2ClientBindings() {
    }

    public static void bind(Consumer<Module> installer,
                            BindingSpec<String> clientIdSpec,
                            BindingSpec<Optional<String>> clientSecretSpec,
                            BindingSpec<String> apiNameSpec,
                            BindingSpec<String> tokenUrlSpec,
                            BindingSpec<Optional<String>> revocationUrlSpec,
                            BindingSpec<String> scopeSpec) {
        clientIdSpec.bind(String.class).annotatedWith(ClientID.class).installedBy(installer);
        clientSecretSpec.bind(new TypeLiteral<>() {}).annotatedWith(ClientSecret.class).installedBy(installer);
        apiNameSpec.bind(String.class).annotatedWith(ApiName.class).installedBy(installer);
        tokenUrlSpec.bind(String.class).annotatedWith(TokenUrl.class).installedBy(installer);
        revocationUrlSpec.bind(new TypeLiteral<>() {}).annotatedWith(RevocationUrl.class).installedBy(installer);
        scopeSpec.bind(String.class).annotatedWith(Scope.class).installedBy(installer);
    }
}
