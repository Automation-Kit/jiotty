package net.yudichev.jiotty.connector.nest;

import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.common.rest.ContentTypes;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.concurrent.CompletableFuture;

import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.rest.RestClients.call;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.shutdown;

final class NestThermostatImpl extends BaseLifecycleComponent implements NestThermostat {
    private static final Logger logger = LogManager.getLogger(NestThermostatImpl.class);

    private final String hvacModeUrl;
    private final String authorization;
    private OkHttpClient client;

    @Inject
    NestThermostatImpl(String accessToken, String deviceId) {
        authorization = "Bearer " + accessToken;
        hvacModeUrl = "https://developer-api.nest.com/devices/thermostats/" + deviceId + "/hvac_mode";
    }

    @Override
    protected void doStart() {
        client = newClient(builder -> builder
                .authenticator((route, response) -> response.request().newBuilder()
                                                            .header(AUTHORIZATION, authorization)
                                                            .build())
                .build());
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, () -> shutdown(client));
    }

    @Override
    public CompletableFuture<Mode> currentMode() {
        return call(client.newCall(new Request.Builder()
                                           .url(hvacModeUrl)
                                           .get()
                                           .addHeader(CONTENT_TYPE, ContentTypes.CONTENT_TYPE_JSON)
//                .addHeader(AUTHORIZATION, "Bearer " + accessToken)
                                           .build()), Mode.class);
    }

    @Override
    public CompletableFuture<Mode> setMode(Mode mode) {
        Request request = new Request.Builder()
                .url(hvacModeUrl)
                .put(RequestBody.create(Json.stringify(mode.id()), MediaType.get(ContentTypes.CONTENT_TYPE_JSON)))
                .addHeader(CONTENT_TYPE, ContentTypes.CONTENT_TYPE_JSON)
//                .addHeader(AUTHORIZATION, accessToken)
                .build();

        return call(client.newCall(request), Mode.class);
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface AccessToken {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface DeviceId {
    }
}
