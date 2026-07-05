package net.yudichev.jiotty.security;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.security.AuthState;

import java.util.Optional;
import java.util.function.Consumer;

public interface OAuth2TokenManager {
    /// The client secret, or [Optional#empty()] for a public client that authenticates via PKCE instead.
    Optional<String> clientSecret();

    String clientId();

    String scope();

    Closeable subscribeToAccessTokenState(Consumer<? super AuthState> handler);

    /// Marks the current credential as permanently unusable: drops the stored token and notifies subscribers of an [AuthState.PermanentFailure], so a caller
    /// that has independently determined the credential is no longer accepted can force re-authentication instead of the token being refreshed
    /// indefinitely. A subsequent [#onNewAuthCode] clears the invalidation. `reason` is a human-readable description of why the credential was rejected.
    void invalidate(String reason);

    /// Supply the new auth code received from the target system after the user logged in. This initiates exchanging this code for an access token.
    default void onNewAuthCode(String authCode, String redirectUri) {
        onNewAuthCode(authCode, redirectUri, Optional.empty());
    }

    /// Supply the new auth code received from the target system after the user logged in, together with the PKCE code verifier that was used to obtain it.
    /// Suitable for public clients that authenticate via PKCE rather than a client secret. This initiates exchanging this code for an access token.
    void onNewAuthCode(String authCode, String redirectUri, Optional<String> codeVerifier);
}
