package net.yudichev.jiotty.security;

import com.google.common.collect.ImmutableMap;
import com.google.inject.BindingAnnotation;
import com.sun.net.httpserver.HttpServer;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import org.jspecify.annotations.Nullable;

import java.io.OutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static net.yudichev.jiotty.security.Bindings.ApiName;
import static net.yudichev.jiotty.security.Bindings.ClientID;
import static net.yudichev.jiotty.security.Bindings.ClientSecret;
import static net.yudichev.jiotty.security.Bindings.Dependency;
import static net.yudichev.jiotty.security.Bindings.Scope;
import static net.yudichev.jiotty.security.Bindings.TokenUrl;

/// [OAuth2TokenManagerImpl] that gets the auth code using redirect to a local temporary HTTP server. This is suitable for enthusiast local automations only,
/// proper multi-user apps should handle logins themselves, then use [OAuth2TokenManagerImpl] and its [OAuth2TokenManager#onNewAuthCode(String, String)] to
/// supply auth codes.
public class LocalLoginOAuth2TokenManager extends OAuth2TokenManagerImpl {
    private final Optional<Integer> fixedCallbackHttpPort;
    private final String loginUrl;
    private final Map<String, String> extraLoginParams;

    /// Non-nullness of this field means we are in the process of obtaining the initial token.
    private @Nullable HttpServer httpServer;
    /// The PKCE code verifier for the in-flight login (public clients only), or `null` when not using PKCE. Written when the login starts, read when the
    /// redirect callback exchanges the code.
    private volatile @Nullable String codeVerifier;

    @Inject
    public LocalLoginOAuth2TokenManager(@Dependency Provider<SchedulingExecutor> executorProvider,
                                        CurrentDateTimeProvider currentDateTimeProvider,
                                        @Dependency VarStore varStore,
                                        @ClientID String clientId,
                                        @ClientSecret Optional<String> clientSecret,
                                        @ApiName String apiName,
                                        @TokenUrl String tokenUrl,
                                        @Scope String scope,
                                        @LoginUrl String loginUrl,
                                        @FixedCallbackHttpPort Optional<Integer> fixedCallbackHttpPort,
                                        @ExtraLoginParams Map<String, String> extraLoginParams) {
        // never login-pending: this manager runs the login itself in obtainAccessToken
        super(executorProvider, currentDateTimeProvider, varStore, clientId, clientSecret, apiName, tokenUrl, scope, false);
        this.loginUrl = checkNotNull(loginUrl);
        this.fixedCallbackHttpPort = checkNotNull(fixedCallbackHttpPort);
        this.extraLoginParams = ImmutableMap.copyOf(extraLoginParams);
    }

    private String startRedirectHttpServer(String state) {
        return getAsUnchecked(() -> {
            int port = fixedCallbackHttpPort.orElseGet(LocalLoginOAuth2TokenManager::findFreeTcpPort);
            httpServer = HttpServer.create(new InetSocketAddress("localhost", port), 0);
            String callbackUrl = "http://localhost:" + httpServer.getAddress().getPort() + "/callback";
            httpServer.createContext("/callback", exchange -> {
                var query = exchange.getRequestURI().getQuery();
                logger.info("[{}] callback received: {}", apiName, query);
                Map<String, String> queryParams = splitQuery(query);
                var stateFromServer = queryParams.get("state");
                String response;
                if (stateFromServer != null && !stateFromServer.equals(state)) {
                    response = "'state' mismatch: expected " + state + ", got " + stateFromServer;
                    exchange.sendResponseHeaders(400, response.length());
                } else {
                    String authCode = queryParams.get("code");
                    if (authCode != null) {
                        onNewAuthCode(authCode, callbackUrl, Optional.ofNullable(codeVerifier));
                        response = "Auth Code Success";
                        exchange.sendResponseHeaders(200, response.length());
                    } else {
                        // probably token callback? TODO test miele
                        logger.info("[{}/{}] token callback received?", apiName, clientId);
                        response = "No Code - token callback?";
                        exchange.sendResponseHeaders(200, response.length());
                    }
                }
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            });
            httpServer.setExecutor(executor); // creates a default executor
            httpServer.start();
            return callbackUrl;
        });
    }

    private static int findFreeTcpPort() {
        return getAsUnchecked(() -> {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            }
        });
    }

    @Override
    protected void obtainAccessToken() {
        if (httpServer != null) {
            logger.info("[{}/{}] already obtaining the access token", apiName, clientId);
            return;
        }

        String state = UUID.randomUUID().toString();
        // authorisation code based process, need to communicate with the user
        String redirectUri = startRedirectHttpServer(state);
        var url = new StringBuilder(loginUrl)
                .append("?response_type=code")
                .append("&client_id=").append(enc(clientId))
                .append("&redirect_uri=").append(enc(redirectUri))
                .append("&scope=").append(enc(scope))
                .append("&state=").append(enc(state));
        // A public client (no secret) authenticates the code exchange via PKCE instead of a secret.
        if (clientSecret().isEmpty()) {
            codeVerifier = generateCodeVerifier();
            url.append("&code_challenge=").append(enc(codeChallenge(codeVerifier))).append("&code_challenge_method=S256");
        } else {
            codeVerifier = null;
        }
        extraLoginParams.forEach((name, value) -> url.append('&').append(enc(name)).append('=').append(enc(value)));
        logger.info("[{}] login required — open this URL in a browser:\n{}", apiName, url);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, US_ASCII);
    }

    private static String generateCodeVerifier() {
        var bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String codeChallenge(String verifier) {
        return getAsUnchecked(() -> Base64.getUrlEncoder().withoutPadding()
                                          .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(US_ASCII))));
    }

    @Override
    protected void setCurrentToken(OauthAccessToken accessToken) {
        // async is better not to delay processing; the stopping of the server is not important
        executor.execute(() -> {
            if (httpServer != null) {
                httpServer.stop(10);
                httpServer = null;
            }
        });
        super.setCurrentToken(accessToken);
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface LoginUrl {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface FixedCallbackHttpPort {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface ExtraLoginParams {
    }
}
