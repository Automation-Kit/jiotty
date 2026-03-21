package net.yudichev.jiotty.connector.world.sun;

import net.yudichev.jiotty.common.geo.LatLon;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.rest.RestClients.call;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.shutdown;
import static okhttp3.HttpUrl.parse;

final class SunriseSunsetTimesImpl extends BaseLifecycleComponent implements SunriseSunsetTimes {
    private static final Logger logger = LogManager.getLogger(SunriseSunsetTimesImpl.class);

    private static final String API_URL = "https://api.sunrise-sunset.org/json";

    private OkHttpClient client;

    @Override
    protected void doStart() {
        client = newClient();
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, () -> shutdown(client));
    }

    @Override
    public CompletableFuture<SunriseSunsetData> getCurrentSunriseSunset(LatLon worldCoordinates) {
        return whenStartedAndNotLifecycling(() -> call(client.newCall(
                new Request.Builder()
                        .url(checkNotNull(parse(API_URL)).newBuilder()
                                                         .addQueryParameter("lat", Double.toString(worldCoordinates.lat()))
                                                         .addQueryParameter("lng", Double.toString(worldCoordinates.lon()))
                                                         .addQueryParameter("formatted", "0")
                                                         .build())
                        .get()
                        .build()), SunriseSunsetResponse.class)
                .thenApply(sunriseSunsetResponse -> {
                    checkState("OK".equals(sunriseSunsetResponse.status()), "status not OK in response %s", sunriseSunsetResponse);
                    checkState(sunriseSunsetResponse.results().isPresent(), "no results in response %s", sunriseSunsetResponse);
                    return sunriseSunsetResponse.results().get();
                }));
    }
}
