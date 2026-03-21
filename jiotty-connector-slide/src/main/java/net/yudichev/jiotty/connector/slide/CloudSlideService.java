package net.yudichev.jiotty.connector.slide;

import com.google.common.reflect.TypeToken;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.noop;
import static net.yudichev.jiotty.common.lang.CompletableFutures.logErrorOnFailure;
import static net.yudichev.jiotty.common.lang.Json.object;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static net.yudichev.jiotty.common.rest.ContentTypes.CONTENT_TYPE_JSON;
import static net.yudichev.jiotty.common.rest.RestClients.call;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.shutdown;
import static net.yudichev.jiotty.connector.slide.Bindings.Email;
import static net.yudichev.jiotty.connector.slide.Bindings.Password;
import static net.yudichev.jiotty.connector.slide.Bindings.ServiceExecutor;

final class CloudSlideService extends BaseLifecycleComponent implements SlideService {
    private static final Logger logger = LogManager.getLogger(CloudSlideService.class);

    private final String email;
    private final String password;
    private final Provider<SchedulingExecutor> executorProvider;
    private OkHttpClient client;

    private String accessToken;
    private Closeable tokenRefreshSchedule = noop();

    @Inject
    CloudSlideService(@ServiceExecutor Provider<SchedulingExecutor> executorProvider,
                      @Email String email,
                      @Password String password) {
        this.executorProvider = checkNotNull(executorProvider);
        this.email = checkNotNull(email);
        this.password = checkNotNull(password);
    }

    @Override
    protected void doStart() {
        client = newClient();
        AuthenticationResponse authenticationResponse = getAsUnchecked(() -> issueRefreshAccessTokenRequest().get(10, TimeUnit.SECONDS));
        accessToken = authenticationResponse.accessToken();
        Duration accessTokenRefreshPeriod = Duration.ofSeconds(authenticationResponse.expiresInSeconds()).minus(Duration.ofDays(1));
        logger.debug("Access token refresh period {}", accessTokenRefreshPeriod);
        tokenRefreshSchedule = executorProvider.get().scheduleAtFixedRate(accessTokenRefreshPeriod, this::refreshAccessToken);
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, tokenRefreshSchedule, () -> shutdown(client));
    }

    @Override
    public CompletableFuture<SlideInfo> getSlideInfo(long slideId, Executor executor) {
        logger.debug("Getting slide info ({})", slideId);
        return call(client.newCall(new Request.Builder()
                                           .url("https://api.goslide.io/api/slide/" + slideId + "/info")
                                           .get()
                                           .header("authorization", "Bearer " + accessToken)
                                           .build()),
                    new TypeToken<SlideResponse<SlideInfo>>() {})
                .thenApplyAsync(response -> response.dataOrThrow("Failed to obtain slide info for id " + slideId), executor);
    }

    @Override
    public CompletableFuture<Void> setSlidePosition(long slideId, double position, Executor executor) {
        logger.debug("Set slide {} position to {}", slideId, position);
        return call(client.newCall(new Request.Builder()
                                           .url("https://api.goslide.io/api/slide/" + slideId + "/position")
                                           .post(RequestBody.create(object()
                                                                            .put("pos", position)
                                                                            .toString(),
                                                                    MediaType.get(CONTENT_TYPE_JSON)))
                                           .header("authorization", "Bearer " + accessToken)
                                           .build()),
                    new TypeToken<SlideResponse<Object>>() {})
                .thenAcceptAsync(response -> response.dataOrThrow("Failed to set slide position for id " + slideId), executor);
    }

    private CompletableFuture<AuthenticationResponse> issueRefreshAccessTokenRequest() {
        logger.info("Refreshing access token");
        return call(client.newCall(new Request.Builder()
                                           .url("https://api.goslide.io/api/auth/login")
                                           .post(RequestBody.create(object()
                                                                            .put("email", email)
                                                                            .put("password", password)
                                                                            .toString(),
                                                                    MediaType.get(CONTENT_TYPE_JSON)))
                                           .build()),
                    AuthenticationResponse.class);
    }

    private void refreshAccessToken() {
        issueRefreshAccessTokenRequest()
                .thenAccept(authenticationResponse -> whenStartedAndNotLifecycling(() -> {accessToken = authenticationResponse.accessToken();}))
                .whenComplete(logErrorOnFailure(logger, "failed to refresh slide access token"));
    }
}
