package net.yudichev.jiotty.security;

import net.yudichev.jiotty.common.lang.Closeable;

import java.util.function.Consumer;

public interface OAuth2TokenManager {
    String clientSecret();

    String clientId();

    String scope();

    Closeable subscribeToAccessTokenState(Consumer<? super AuthState> handler);

    /// Supply the new auth code received from the target system after the user logged in. This initiates exchanging this code for an access token.
    void onNewAuthCode(String authCode, String redirectUri);
}
