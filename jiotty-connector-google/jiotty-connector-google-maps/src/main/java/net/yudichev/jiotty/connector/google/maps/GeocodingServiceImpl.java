package net.yudichev.jiotty.connector.google.maps;

import com.google.common.collect.ImmutableList;
import com.google.maps.GeoApiContext;
import com.google.maps.GeocodingApi;
import com.google.maps.PendingResult;
import com.google.maps.model.GeocodingResult;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.geo.LatLon;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.connector.google.maps.Bindings.ApiKey;

public final class GeocodingServiceImpl extends BaseLifecycleComponent implements GeocodingService {
    private static final Logger logger = LogManager.getLogger(GeocodingServiceImpl.class);

    private final String apiKey;
    private final AtomicInteger reqIdGen = new AtomicInteger();
    private GeoApiContext context;

    @Inject
    public GeocodingServiceImpl(@ApiKey String apiKey) {
        this.apiKey = checkNotNull(apiKey);
    }

    @Override
    protected void doStart() {
        context = new GeoApiContext.Builder().apiKey(apiKey).build();
    }

    @Override
    public CompletableFuture<List<LatLon>> geocode(String address) {
        var resultFut = new CompletableFuture<List<LatLon>>();
        int reqId = reqIdGen.incrementAndGet();
        logger.debug("[{}] Requesting geocode of address '{}'", reqId, address);
        GeocodingApi.geocode(context, address).setCallback(new PendingResult.Callback<>() {
            @Override
            public void onResult(GeocodingResult[] result) {
                if (result.length == 0) {
                    resultFut.complete(List.of());
                } else {
                    var resultBuilder = ImmutableList.<LatLon>builderWithExpectedSize(result.length);
                    for (GeocodingResult geocodingResult : result) {
                        resultBuilder.add(new LatLon(geocodingResult.geometry.location.lat, geocodingResult.geometry.location.lng));
                    }
                    resultFut.complete(resultBuilder.build());
                }
            }

            @Override
            public void onFailure(Throwable e) {
                resultFut.completeExceptionally(e);
            }
        });
        if (logger.isDebugEnabled()) {
            resultFut.whenComplete((results, throwable) -> logger.debug("[{}] result: {}", reqId, results, throwable));
        }
        return resultFut;
    }

    @Override
    protected void doStop() {
        Closeable.closeSafelyIfNotNull(logger, context);
    }
}
