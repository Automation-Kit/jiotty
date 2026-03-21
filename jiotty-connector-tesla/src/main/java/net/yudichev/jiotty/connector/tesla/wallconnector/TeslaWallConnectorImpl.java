package net.yudichev.jiotty.connector.tesla.wallconnector;

import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static net.yudichev.jiotty.common.rest.RestClients.call;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.shutdown;

public final class TeslaWallConnectorImpl extends BaseLifecycleComponent implements TeslaWallConnector {
    private static final Logger logger = LogManager.getLogger(TeslaWallConnectorImpl.class);
    private final URL vitalsUrl;

    private final AtomicLong requestIdGen = new AtomicLong();
    private OkHttpClient httpClient;

    @Inject
    public TeslaWallConnectorImpl(@HostAddress String hostAddress) {
        vitalsUrl = getAsUnchecked(() -> new URI("http", hostAddress, "/api/1/vitals", null).toURL());
    }

    @Override
    protected void doStart() {
        httpClient = newClient();
    }

    @Override
    protected void doStop() {
        Closeable.closeSafelyIfNotNull(logger, () -> shutdown(httpClient));
    }

    @Override
    public CompletableFuture<TeslaWallConnectorVitals> getVitals() {
        long requestId = requestIdGen.incrementAndGet();
        logger.debug("[{}] Requesting", requestId);
        var result = call(httpClient.newCall(new Request.Builder()
                                                     .url(vitalsUrl)
                                                     .get()
                                                     .build()),
                          TeslaWallConnectorVitals.class);
        if (logger.isDebugEnabled()) {
            result.whenComplete((vitals, e) -> logger.debug("[{}] Result: {}", vitals, e));
        }
        return result;
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface HostAddress {
    }

}
