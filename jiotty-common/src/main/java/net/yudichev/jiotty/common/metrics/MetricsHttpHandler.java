package net.yudichev.jiotty.common.metrics;

import com.google.inject.BindingAnnotation;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.rest.RestServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

public final class MetricsHttpHandler extends BaseLifecycleComponent {
    private static final Logger logger = LogManager.getLogger(MetricsHttpHandler.class);
    private static final String METRICS_PATH = "/metrics";
    private static final String EXPOSITION_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    @Inject
    public MetricsHttpHandler(@Dependency RestServer restServer, PrometheusMeterRegistry registry) {
        checkNotNull(restServer, "restServer");
        checkNotNull(registry, "registry");
        restServer.get(METRICS_PATH, ctx -> {
            ctx.contentType(EXPOSITION_CONTENT_TYPE);
            registry.scrape(ctx.outputStream());
        });
        logger.info("Registered {} on admin REST server", METRICS_PATH);
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }
}
