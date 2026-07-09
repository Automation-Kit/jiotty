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

    /// Marks the credential as permanently unusable — but only if `rejectedAccessToken` is still the manager's current access token: drops the stored token and
    /// notifies subscribers of an [AuthState.PermanentFailure], so a caller that has independently determined the credential is no longer accepted can force
    /// re-authentication instead of the token being refreshed indefinitely. A subsequent [#onNewAuthCode] clears the invalidation. When `rejectedAccessToken`
    /// does not match the current token — because no token has been obtained yet (an auth-code exchange is still in flight) or because a refresh has already
    /// replaced it — the call is a no-op: the rejection was against a credential the manager has already moved on from, so honouring it would tear down a token
    /// that was never the one rejected. `reason` is a human-readable description of why the credential was rejected.
    void invalidate(String rejectedAccessToken, String reason);

    /// Supply the new auth code received from the target system after the user logged in. This initiates exchanging this code for an access token.
    default void onNewAuthCode(String authCode, String redirectUri) {
        onNewAuthCode(authCode, redirectUri, Optional.empty());
    }

    /// Supply the new auth code received from the target system after the user logged in, together with the PKCE code verifier that was used to obtain it.
    /// Suitable for public clients that authenticate via PKCE rather than a client secret. This initiates exchanging this code for an access token.
    void onNewAuthCode(String authCode, String redirectUri, Optional<String> codeVerifier);
}
