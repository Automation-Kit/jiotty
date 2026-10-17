package net.yudichev.jiotty.security;

import com.google.inject.Module;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.inject.BindingSpec;

import java.util.Optional;
import java.util.function.Consumer;

import static net.yudichev.jiotty.security.Bindings.ApiName;
import static net.yudichev.jiotty.security.Bindings.ClientSecret;
import static net.yudichev.jiotty.security.Bindings.RevocationUrl;
import static net.yudichev.jiotty.security.Bindings.Scope;

/// Binds what [OAuth2TokenManagerImpl] and [StoredGrantRevokerImpl] both read about one OAuth2 client of one API, so the two key a stored token alike and
/// reach the same endpoints. Each binds the client id and the token URL itself, in the form it needs.
final class OAuth2ClientBindings {
    private OAuth2ClientBindings() {
    }

    public static void bind(Consumer<Module> installer,
                            BindingSpec<Optional<String>> clientSecretSpec,
                            BindingSpec<String> apiNameSpec,
                            BindingSpec<Optional<String>> revocationUrlSpec,
                            BindingSpec<String> scopeSpec) {
        clientSecretSpec.bind(new TypeLiteral<>() {}).annotatedWith(ClientSecret.class).installedBy(installer);
        apiNameSpec.bind(String.class).annotatedWith(ApiName.class).installedBy(installer);
        revocationUrlSpec.bind(new TypeLiteral<>() {}).annotatedWith(RevocationUrl.class).installedBy(installer);
        scopeSpec.bind(String.class).annotatedWith(Scope.class).installedBy(installer);
    }
}
