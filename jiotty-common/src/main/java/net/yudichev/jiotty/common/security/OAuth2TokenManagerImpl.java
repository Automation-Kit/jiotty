package net.yudichev.jiotty.common.security;

import com.google.common.reflect.TypeToken;
import com.google.inject.BindingAnnotation;
import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Listeners;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.common.varstore.VarStore;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static net.yudichev.jiotty.common.lang.CompletableFutures.logErrorOnFailure;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static net.yudichev.jiotty.common.rest.RestClients.call;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.shutdown;

public class OAuth2TokenManagerImpl extends BaseLifecycleComponent implements OAuth2TokenManager {
    private static final Logger logger = LogManager.getLogger(OAuth2TokenManagerImpl.class);

    private final ExecutorFactory executorFactory;
    private final VarStore varStore;
    private final String clientId;
    private final String clientSecret;
    private final CurrentDateTimeProvider currentDateTimeProvider;
    private final Listeners<String> listeners = new Listeners<>();
    private final String varStoreKey;
    private final String apiName;
    private final String loginUrl;
    private final String tokenUrl;
    private final String scope;
    private final Optional<Integer> fixedCallbackHttpPort;
    private OkHttpClient httpClient;

    private OauthAccessToken currentToken;
    private SchedulingExecutor executor;
    /// Non-nullness of this field means we are in the process of obtaining the initial token.
    @Nullable
    private HttpServer httpServer;

    @Inject
    public OAuth2TokenManagerImpl(ExecutorFactory executorFactory,
                                  CurrentDateTimeProvider currentDateTimeProvider,
                                  VarStore varStore,
                                  @ClientID String clientId,
                                  @ClientSecret String clientSecret,
                                  @ApiName String apiName,
                                  @LoginUrl String loginUrl,
                                  @TokenUrl String tokenUrl,
                                  @Scope String scope,
                                  @FixedCallbackHttpPort Optional<Integer> fixedCallbackHttpPort) {
        this.clientId = checkNotNull(clientId);
        this.clientSecret = checkNotNull(clientSecret);
        this.executorFactory = checkNotNull(executorFactory);
        this.currentDateTimeProvider = checkNotNull(currentDateTimeProvider);
        this.varStore = checkNotNull(varStore);
        this.apiName = checkNotNull(apiName);
        this.loginUrl = checkNotNull(loginUrl);
        this.scope = checkNotNull(scope);
        this.tokenUrl = checkNotNull(tokenUrl);
        this.fixedCallbackHttpPort = checkNotNull(fixedCallbackHttpPort);
        varStoreKey = apiName + "Oauth2Token_" + clientId + "_" + scope;
    }

    @Override
    protected void doStart() {
        httpClient = newClient();
        executor = executorFactory.createSingleThreadedSchedulingExecutor(apiName + "-oauth2");
        varStore.readValue(OauthAccessToken.class, varStoreKey)
                .ifPresentOrElse(accessToken -> {
                                     if (isExpired(accessToken)) {
                                         refreshAccessToken(accessToken.refreshToken());
                                     } else {
                                         setCurrentToken(accessToken);
                                         scheduleTokenRefresh();
                                     }
                                 },
                                 this::obtainAccessToken);
    }

    @Override
    public String clientSecret() {
        return clientSecret;
    }

    @Override
    public String clientId() {
        return clientId;
    }

    @Override
    public String scope() {
        return scope;
    }

    @SuppressWarnings("TypeMayBeWeakened")
    private boolean isExpired(OauthAccessToken accessToken) {
        return currentDateTimeProvider.currentInstant().isAfter(accessToken.expiryTime());
    }

    @Override
    protected void doStop() {
        Closeable.closeSafelyIfNotNull(logger, executor, () -> shutdown(httpClient));
    }

    @Override
    public Closeable subscribeToAccessToken(Consumer<? super String> accessTokenHandler) {
        return whenStartedAndNotLifecycling(() -> listeners.addListener(executor,
                                                                        () -> Optional.ofNullable(currentToken).map(OauthAccessToken::accessToken),
                                                                        accessTokenHandler));
    }

    private void obtainAccessToken() {
        if (httpServer != null) {
            logger.info("[{}] already obtaining the access token", clientId);
            return;
        }

        String state = UUID.randomUUID().toString();
        // authorisation code based process, need to communicate with the user
        String callbackUrl = startRedirectHttpServer(state);
        logger.warn("{} login required: {}?response_type=code&client_id={}&redirect_uri={}&scope={}&state={}",
                    apiName, loginUrl,
                    URLEncoder.encode(clientId, US_ASCII),
                    URLEncoder.encode(callbackUrl, US_ASCII),
                    URLEncoder.encode(scope, US_ASCII),
                    URLEncoder.encode(state, US_ASCII));
    }

    private String startRedirectHttpServer(String state) {
        return getAsUnchecked(() -> {
            int port = fixedCallbackHttpPort.orElseGet(() -> getAsUnchecked(OAuth2TokenManagerImpl::findFreeTcpPort));
            httpServer = HttpServer.create(new InetSocketAddress("localhost", port), 0);
            String callbackUrl = "http://localhost:" + httpServer.getAddress().getPort() + "/callback";
            httpServer.createContext("/callback", exchange -> {
                var query = exchange.getRequestURI().getQuery();
                logger.info("[{}] callback received: {}", clientId, query);
                Map<String, String> queryParams = splitQuery(query);
                var stateFromServer = queryParams.get("state");
                String response;
                if (stateFromServer != null && !stateFromServer.equals(state)) {
                    response = "'state' mismatch: expected " + state + ", got " + stateFromServer;
                    exchange.sendResponseHeaders(400, response.length());
                } else {
                    String authCode = queryParams.get("code");
                    if (authCode != null) {
                        onAuthCodeReceived(authCode, callbackUrl);
                        response = "Auth Code Success";
                        exchange.sendResponseHeaders(200, response.length());
                    } else {
                        // probably token callback? TODO test miele
//                        response = "No 'code' in query " + query;
//                        exchange.sendResponseHeaders(400, response.length());
                        logger.info("[{}] token callback received?", clientId);
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

    public static Map<String, String> splitQuery(String query) {
        var queryValuesByKey = new LinkedHashMap<String, String>();
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            queryValuesByKey.put(URLDecoder.decode(pair.substring(0, idx), US_ASCII),
                                 URLDecoder.decode(pair.substring(idx + 1), US_ASCII));
        }
        return queryValuesByKey;
    }

    private static InetAddress getOutboundAddress() {
        return getAsUnchecked(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.connect(InetAddress.getByName("8.8.8.8"), 10002);
                return socket.getLocalAddress();
            }
        });
    }

    private void onAuthCodeReceived(String authCode, String callbackUrl) {
        logger.info("[{}] auth code received", clientId);
        assert httpServer != null;
        requestToken(new FormBody.Builder()
                             .add("grant_type", "authorization_code")
                             .add("code", authCode)
                             .add("redirect_uri", callbackUrl)
                             .add("client_id", clientId)
                             .add("client_secret", clientSecret)
                             .build());
    }

    public static int findFreeTcpPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }


    // Function to refresh the access token

    private void refreshAccessToken(String refreshToken) {
        requestToken(new FormBody.Builder()
                             .add("grant_type", "refresh_token")
                             .add("refresh_token", refreshToken)
                             .add("client_id", clientId)
                             .add("client_secret", clientSecret)
                             .build());
    }

    private void requestToken(RequestBody formBody) {
        logger.info("[{}] requesting token", clientId);
        var request = new Request.Builder().url(tokenUrl)
                                           .post(formBody)
                                           .build();

        Instant requestTime = currentDateTimeProvider.currentInstant();
        call(httpClient.newCall(request), new TypeToken<OauthAccessTokenResponse>() {})
                .thenAcceptAsync(response -> {
                    logger.info("[{}] token received", clientId);
                    // async is better not to delay processing; the stopping of the server is not important
                    executor.execute(() -> {
                        if (httpServer != null) {
                            httpServer.stop(10);
                            httpServer = null;
                        }
                    });
                    setCurrentToken(responseToToken(requestTime, response));
                    varStore.saveValue(varStoreKey, currentToken);
                    scheduleTokenRefresh();
                }, executor)
                .whenComplete(logErrorOnFailure(logger, "Failed to obtain token for client %s", clientId));
    }

    private OauthAccessToken responseToToken(Instant currentTime, OauthAccessTokenResponse response) {
        Duration refreshThisLongBeforeExpiry = Duration.ofSeconds(response.expiresInSec() / 10 * 8);
        Instant expiryTime = currentTime.plusSeconds(response.expiresInSec());
        Instant refreshTime = expiryTime.minus(refreshThisLongBeforeExpiry);
        checkArgument(refreshThisLongBeforeExpiry.compareTo(Duration.ofMinutes(1)) >= 0,
                      """
                      Client ID %s: OAuth2 response token's 'expires in, seconds' value %s is too small:\
                      the resulting token refresh time %s is too close to expiry time %s""",
                      clientId, response.expiresInSec(), refreshTime, expiryTime);
        return OauthAccessToken.of(response.accessToken(), response.refreshToken(), refreshTime);
    }

    private void setCurrentToken(OauthAccessToken accessToken) {
        logger.debug("[{}] token set, expires at {}", clientId, accessToken.expiryTime());
        currentToken = accessToken;
        listeners.notify(currentToken.accessToken());
    }

    private void scheduleTokenRefresh() {
        var expiryDelay = Duration.between(currentDateTimeProvider.currentInstant(), currentToken.expiryTime());
        logger.info("[{}] will refresh token in {} ({})", clientId, expiryDelay, currentToken.expiryTime());
        executor.schedule(expiryDelay, () -> refreshAccessToken(currentToken.refreshToken()));
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface ClientID {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface ClientSecret {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface ApiName {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface LoginUrl {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface TokenUrl {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Scope {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface FixedCallbackHttpPort {
    }
}
