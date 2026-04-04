package net.yudichev.jiotty.security;

import com.google.inject.BindingAnnotation;
import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.persistence.varstore.VarStore;

import java.io.OutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLEncoder;
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
import static net.yudichev.jiotty.security.Bindings.Scope;
import static net.yudichev.jiotty.security.Bindings.TokenUrl;

/// [OAuth2TokenManagerImpl] that gets the auth code using redirect to a local temporary HTTP server. This is suitable for enthusiast local automations only,
/// proper multi-user apps should handle logins themselves, then use [OAuth2TokenManagerImpl] and its [OAuth2TokenManager#onNewAuthCode(String, String)] to
/// supply auth codes.
public class LocalLoginOAuth2TokenManager extends OAuth2TokenManagerImpl {
    private final Optional<Integer> fixedCallbackHttpPort;
    private final String loginUrl;

    /// Non-nullness of this field means we are in the process of obtaining the initial token.
    @Nullable
    private HttpServer httpServer;

    @Inject
    public LocalLoginOAuth2TokenManager(ExecutorFactory executorFactory,
                                        CurrentDateTimeProvider currentDateTimeProvider,
                                        VarStore varStore,
                                        @ClientID String clientId,
                                        @ClientSecret String clientSecret,
                                        @ApiName String apiName,
                                        @TokenUrl String tokenUrl,
                                        @Scope String scope,
                                        @LoginUrl String loginUrl,
                                        @FixedCallbackHttpPort Optional<Integer> fixedCallbackHttpPort) {
        super(executorFactory, currentDateTimeProvider, varStore, clientId, clientSecret, apiName, tokenUrl, scope);
        this.loginUrl = checkNotNull(loginUrl);
        this.fixedCallbackHttpPort = checkNotNull(fixedCallbackHttpPort);
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
                        onNewAuthCode(authCode, callbackUrl);
                        response = "Auth Code Success";
                        exchange.sendResponseHeaders(200, response.length());
                    } else {
                        // probably token callback? TODO test miele
//                        response = "No 'code' in query " + query;
//                        exchange.sendResponseHeaders(400, response.length());
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
        logger.warn("{} login required: {}?response_type=code&client_id={}&redirect_uri={}&scope={}&state={}",
                    apiName, loginUrl,
                    URLEncoder.encode(clientId, US_ASCII),
                    URLEncoder.encode(redirectUri, US_ASCII),
                    URLEncoder.encode(scope, US_ASCII),
                    URLEncoder.encode(state, US_ASCII));
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
}
