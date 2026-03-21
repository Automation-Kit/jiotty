package net.yudichev.jiotty.connector.octopusenergy.agilepredict;

import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.rest.RestClients;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.base.Verify.verify;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.shutdown;

public final class AgilePredictPriceServiceImpl extends BaseLifecycleComponent implements AgilePredictPriceService {
    private static final Logger logger = LogManager.getLogger(AgilePredictPriceServiceImpl.class);
    private final AtomicInteger requestIdGenerator = new AtomicInteger();
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
    public CompletableFuture<List<AgilePredictPrice>> getPrices(String region, int dayCount) {
        verify(region.length() == 1 && Character.isLetter(region.charAt(0)), "Invalid region: '%s', must be a a single letter", region);
        verify(dayCount >= 1 && dayCount <= 365, "Must have 365 >= dayCount >= 1 but was: %s", dayCount);
        var url = "https://agilepredict.com/api/" + region + "?days=" + dayCount + "&high_low=false";
        var requestId = requestIdGenerator.incrementAndGet();
        logger.debug("[{}] Calling {}", requestId, url);
        return RestClients.call(client.newCall(new Request.Builder().url(url).get().build()),
                                new TypeToken<List<AgilePredictPrices>>() {})
                          .thenApply(agilePredictPricesList -> {
                              logger.debug("[{}] Result: {}", requestId, agilePredictPricesList);
                              return agilePredictPricesList.getFirst().prices();
                          });
    }
}
