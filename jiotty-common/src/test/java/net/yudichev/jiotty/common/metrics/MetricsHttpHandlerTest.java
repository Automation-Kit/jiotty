package net.yudichev.jiotty.common.metrics;

import io.javalin.http.Handler;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import net.yudichev.jiotty.common.rest.RestServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/// Sibling test for [MetricsHttpHandler]. Covers the constructor's observable side effect — registering a `/metrics` route on the injected [RestServer].
///
/// The handler lambda's body (`ctx.contentType(...); ctx.result(registry.scrape());`) is three trivial delegations and is exercised end-to-end by the
/// in-stack `MainModuleTest` integration wiring plus the actual `/metrics` scrape served by the running server — instrumenting it with a unit test here is
/// not worth the Micrometer-mocking gymnastics (`PrometheusMeterRegistry.scrape()` reaches into a Prometheus-client format-writer chain that doesn't play
/// cleanly with Mockito's strict-stubbing checker, and the dependency-chain quirk it surfaces is Micrometer's contract, not ours).
@ExtendWith(MockitoExtension.class)
class MetricsHttpHandlerTest {
    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    @Mock
    private RestServer restServer;

    @Test
    void constructorRegistersMetricsRouteOnTheRestServer() {
        new MetricsHttpHandler(restServer, registry);

        verify(restServer).get(eq("/metrics"), any(Handler.class));
    }
}
