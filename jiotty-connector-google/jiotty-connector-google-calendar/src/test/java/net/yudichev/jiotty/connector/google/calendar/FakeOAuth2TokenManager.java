package net.yudichev.jiotty.connector.google.calendar;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.security.OAuth2TokenManager;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;

/// A minimal in-test [OAuth2TokenManager] that publishes a caller-controlled [AuthState] to its subscribers, so calendar-fetching tests (and the local runner)
/// drive the service's access token without a real OAuth2 exchange.
final class FakeOAuth2TokenManager implements OAuth2TokenManager {
    private final CopyOnWriteArrayList<Consumer<? super AuthState>> handlers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> invalidations = new CopyOnWriteArrayList<>();
    private volatile AuthState state;

    FakeOAuth2TokenManager(AuthState initialState) {
        state = checkNotNull(initialState);
    }

    void setState(AuthState newState) {
        state = checkNotNull(newState);
        handlers.forEach(handler -> handler.accept(newState));
    }

    @Override
    public Optional<String> clientSecret() {
        return Optional.empty();
    }

    @Override
    public String clientId() {
        return "fake-client-id";
    }

    @Override
    public String scope() {
        return "fake-scope";
    }

    @Override
    public Closeable subscribeToAccessTokenState(Consumer<? super AuthState> handler) {
        handlers.add(handler);
        handler.accept(state);
        return () -> handlers.remove(handler);
    }

    @Override
    public void onNewAuthCode(String authCode, String redirectUri, Optional<String> codeVerifier) {
        // no-op: the fake's token state is driven directly via the constructor / setState
    }

    @Override
    public void invalidate(String reason) {
        invalidations.add(reason);
        // Mirror the real manager: an invalidation drops the credential and escalates to a permanent failure.
        setState(new AuthState.PermanentFailure(reason));
    }

    List<String> invalidations() {
        return invalidations;
    }
}
