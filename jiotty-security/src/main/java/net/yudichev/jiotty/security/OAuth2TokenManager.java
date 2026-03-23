package net.yudichev.jiotty.security;

import net.yudichev.jiotty.common.lang.Closeable;

import java.util.function.Consumer;

public interface OAuth2TokenManager {
    String clientSecret();

    String clientId();

    String scope();

    Closeable subscribeToAccessToken(Consumer<? super String> accessTokenHandler);
}
