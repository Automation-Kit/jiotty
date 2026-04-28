package net.yudichev.jiotty.adminalerts.http;

import jakarta.inject.Provider;
import net.yudichev.jiotty.adminalerts.AdminAlert;
import net.yudichev.jiotty.adminalerts.AdminAlertData;
import net.yudichev.jiotty.adminalerts.AdminAlertServiceImpl;
import net.yudichev.jiotty.adminalerts.AdminAlertSeverity;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.SingleThreadedSchedulingExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.time.TimeProvider;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainMigrator;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainServiceImpl;
import net.yudichev.jiotty.persistence.test.EmbeddedPostgresExtension;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;

import static java.util.concurrent.TimeUnit.SECONDS;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static org.assertj.core.api.Assertions.assertThat;

class AdminResolveServletTest {
    private static final String VALID_TOKEN = "tok-correct";
    private static final String GRAFANA_USER = "alice@example.com";
    private static final String DOMAIN_NAME = "admin_alerts";

    @RegisterExtension
    private static final EmbeddedPostgresExtension postgres = new EmbeddedPostgresExtension();

    private SchedulingExecutor executor;
    private PersistenceDomainServiceImpl domainService;
    private AdminAlertServiceImpl alertService;
    private Server server;
    private HttpClient httpClient;
    private int port;

    @BeforeEach
    void setUp() {
        executor = new SingleThreadedSchedulingExecutor("admin-alerts-it");
        Provider<SchedulingExecutor> executorProvider = () -> executor;
        DataSourceFactory dataSourceFactory = postgres.dataSourceFactory();
        domainService = new PersistenceDomainServiceImpl(dataSourceFactory, executorProvider);
        domainService.start();
        alertService = new AdminAlertServiceImpl(dataSourceFactory,
                                                 executorProvider,
                                                 domainService,
                                                 new TimeProvider(),
                                                 1,
                                                 DOMAIN_NAME,
                                                 PersistenceDomainMigrator.FAIL_ON_MIGRATION);
        alertService.start();

        var mount = new AdminAlertServletMount(new AdminBearerAuthFilter(VALID_TOKEN), new AdminResolveServlet(alertService));
        server = new Server();
        var connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);
        server.setHandler(new Handler.Sequence(mount.buildHandler()));
        asUnchecked(server::start);
        port = connector.getLocalPort();
        httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @AfterEach
    void tearDown() {
        Closeable.closeIfNotNull(server == null ? null : () -> asUnchecked(server::stop),
                                 alertService == null ? null : alertService::stop,
                                 domainService == null ? null : domainService::stop,
                                 executor);
    }

    @Test
    void missingBearer_returns401() {
        String alertId = raiseActive("k1");

        HttpResponse<String> response = sendResolve(alertId, null, GRAFANA_USER);

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void wrongBearer_returns401() {
        String alertId = raiseActive("k1");

        HttpResponse<String> response = sendResolve(alertId, "wrong-token", GRAFANA_USER);

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void unknownId_returns404() {
        HttpResponse<String> response = sendResolve("a-nonexistent", VALID_TOKEN, GRAFANA_USER);

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void alreadyResolved_returns409() {
        String alertId = raiseActive("k1");
        getAsUnchecked(() -> alertService.resolve("k1", Optional.empty()).get(10, SECONDS));

        HttpResponse<String> response = sendResolve(alertId, VALID_TOKEN, GRAFANA_USER);

        assertThat(response.statusCode()).isEqualTo(409);
    }

    @Test
    void activeAlert_resolvesAndStampsGrafanaUser() {
        String alertId = raiseActive("k1");

        HttpResponse<String> response = sendResolve(alertId, VALID_TOKEN, GRAFANA_USER);

        assertThat(response.statusCode()).isEqualTo(204);
        AdminAlert alert = getAsUnchecked(() -> alertService.getById(alertId).get(10, SECONDS)).orElseThrow();
        assertThat(alert.resolvedBy()).contains(GRAFANA_USER);
        assertThat(alert.resolvedAt()).isPresent();
    }

    @Test
    void missingGrafanaUserHeader_resolvesWithFallbackIdentity() {
        String alertId = raiseActive("k1");

        HttpResponse<String> response = sendResolve(alertId, VALID_TOKEN, null);

        assertThat(response.statusCode()).isEqualTo(204);
        AdminAlert alert = getAsUnchecked(() -> alertService.getById(alertId).get(10, SECONDS)).orElseThrow();
        assertThat(alert.resolvedBy()).contains(AdminBearerAuthFilter.DEFAULT_GRAFANA_USER);
    }

    private String raiseActive(String dedupKey) {
        var data = new AdminAlertData(dedupKey, "title", "desc", AdminAlertSeverity.ERROR, Map.of("category", "my-category"));
        return getAsUnchecked(() -> alertService.raise(data).get(10, SECONDS));
    }

    private HttpResponse<String> sendResolve(String alertId, String bearerToken, String grafanaUser) {
        var requestBuilder = HttpRequest.newBuilder()
                                        .uri(URI.create("http://localhost:" + port + "/admin/api/alerts/" + alertId + "/resolve"))
                                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                        .header("Content-Type", "application/json");
        if (bearerToken != null) {
            requestBuilder.header("Authorization", "Bearer " + bearerToken);
        }
        if (grafanaUser != null) {
            requestBuilder.header("X-Grafana-User", grafanaUser);
        }
        return getAsUnchecked(() -> httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString()));
    }
}
