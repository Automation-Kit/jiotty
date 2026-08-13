package net.yudichev.jiotty.adminalerts.http;

import jakarta.inject.Provider;
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
import net.yudichev.jiotty.persistence.test.UsingEmbeddedPostgres;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static net.yudichev.jiotty.common.rest.HttpStatuses.CONFLICT_409;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NOT_FOUND_404;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NO_CONTENT_204;
import static net.yudichev.jiotty.common.rest.HttpStatuses.PAYLOAD_TOO_LARGE_413;
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@UsingEmbeddedPostgres
class AdminAlertResolveServletTest {
    private static final String VALID_TOKEN = "tok-correct";
    private static final String GRAFANA_USER = "alice@example.com";
    private static final String DOMAIN_NAME = "admin_alerts";

    @RegisterExtension
    private static final EmbeddedPostgresExtension postgres = new EmbeddedPostgresExtension();

    private SchedulingExecutor executor;
    private PersistenceDomainServiceImpl domainService;
    private DataSourceFactory dataSourceFactory;
    private AdminAlertServiceImpl alertService;
    private Server server;
    private HttpClient httpClient;
    private int port;

    @BeforeEach
    void setUp() {
        executor = new SingleThreadedSchedulingExecutor("admin-alerts-it");
        Provider<SchedulingExecutor> executorProvider = () -> executor;
        dataSourceFactory = postgres.dataSourceFactory();
        domainService = new PersistenceDomainServiceImpl(dataSourceFactory, executorProvider);
        domainService.start();
        alertService = new AdminAlertServiceImpl(dataSourceFactory,
                                                 executorProvider,
                                                 domainService,
                                                 new TimeProvider(),
                                                 2,
                                                 DOMAIN_NAME,
                                                 PersistenceDomainMigrator.FAIL_ON_MIGRATION,
                                                 100,
                                                 100);
        alertService.start();

        var mount = new AdminAlertResolveServletMount(new AdminBearerAuthFilter(VALID_TOKEN), new AdminAlertResolveServlet(alertService));
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

    @ParameterizedTest
    @MethodSource
    void unacceptableAuthorization_returns401(String authorization) {
        String alertId = raiseActive();

        HttpResponse<String> response = sendResolveAuthorized(alertId, authorization, GRAFANA_USER, "{}");

        assertThat(response.statusCode()).isEqualTo(UNAUTHORIZED_401);
    }

    static Stream<Arguments> unacceptableAuthorization_returns401() {
        return Stream.of(arguments((String) null),             // no Authorization header at all
                         arguments("Basic dXNlcjpwYXNz"),      // a scheme other than Bearer
                         arguments("Bearer wrong-token"));     // the Bearer scheme carrying the wrong token
    }

    @Test
    void blankToken_isRejectedAtConstruction() {
        assertThatThrownBy(() -> new AdminBearerAuthFilter("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownId_returns404() {
        HttpResponse<String> response = sendResolve("a-nonexistent", GRAFANA_USER);

        assertThat(response.statusCode()).isEqualTo(NOT_FOUND_404);
    }

    @Test
    void alreadyResolved_returns409() {
        String alertId = raiseActive();
        String key = readDedupKey(alertId);
        getAsUnchecked(() -> alertService.resolve(key, "note").get(10, SECONDS));

        HttpResponse<String> response = sendResolve(alertId, GRAFANA_USER);

        assertThat(response.statusCode()).isEqualTo(CONFLICT_409);
    }

    @Test
    void activeAlert_resolvesAndStampsGrafanaUser() {
        String alertId = raiseActive();

        HttpResponse<String> response = sendResolve(alertId, GRAFANA_USER);

        assertThat(response.statusCode()).isEqualTo(NO_CONTENT_204);
        assertThat(readResolvedBy(alertId)).contains(GRAFANA_USER);
    }

    /// The header is asserted by whoever holds the bearer token, so a claim the audit column cannot keep verbatim is recorded as unknown.
    @ParameterizedTest
    @MethodSource
    void unusableGrafanaUserHeader_resolvesWithFallbackIdentity(String grafanaUser) {
        String alertId = raiseActive();

        HttpResponse<String> response = sendResolve(alertId, grafanaUser);

        assertThat(response.statusCode()).isEqualTo(NO_CONTENT_204);
        assertThat(readResolvedBy(alertId)).contains(AdminBearerAuthFilter.DEFAULT_GRAFANA_USER);
    }

    static Stream<Arguments> unusableGrafanaUserHeader_resolvesWithFallbackIdentity() {
        return Stream.of(arguments((String) null),                    // header absent
                         arguments("   "),                            // whitespace only
                         arguments("alice@example.com<script>"),      // characters a login never carries
                         arguments("a".repeat(AdminBearerAuthFilter.MAX_CLAIMED_ACTOR_LENGTH + 1)));  // one over the ceiling
    }

    @Test
    void oversizedBody_returns413AndDoesNotResolve() {
        String alertId = raiseActive();
        String oversizedBody = "{\"note\":\"" + "x".repeat(9 * 1024) + "\"}";

        HttpResponse<String> response = sendResolve(alertId, GRAFANA_USER, oversizedBody);

        assertThat(response.statusCode()).isEqualTo(PAYLOAD_TOO_LARGE_413);
        assertThat(readResolvedBy(alertId)).isEmpty();
    }

    private String readDedupKey(String alertId) {
        return readColumnById(alertId, "dedup_key").orElseThrow();
    }

    private Optional<String> readResolvedBy(String alertId) {
        return readColumnById(alertId, "resolved_by");
    }

    private Optional<String> readColumnById(String alertId, String column) {
        try (Connection connection = dataSourceFactory.create().getConnection();
             PreparedStatement stmt = connection.prepareStatement(
                     "SELECT " + column + " FROM admin_alerts_alert WHERE id = ?")) {
            stmt.setString(1, alertId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.ofNullable(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private String readIdByKey(String key) {
        try (Connection connection = dataSourceFactory.create().getConnection();
             PreparedStatement stmt = connection.prepareStatement(
                     "SELECT id FROM admin_alerts_alert WHERE dedup_key = ? ORDER BY first_seen_at DESC LIMIT 1")) {
            stmt.setString(1, key);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("No alert with key " + key);
                }
                return rs.getString(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void flush() {
        getAsUnchecked(() -> executor.submit(() -> {}).get(10, SECONDS));
    }

    private String raiseActive() {
        var data = AdminAlertData.builder()
                                 .setSeverity(AdminAlertSeverity.ERROR)
                                 .setTitle("title")
                                 .setDescription("desc")
                                 .setLabels(Map.of("category", "my-category"))
                                 .build();
        String key = alertService.raise(data);
        flush();
        return readIdByKey(key);
    }

    private HttpResponse<String> sendResolve(String alertId, @Nullable String grafanaUser) {
        return sendResolve(alertId, grafanaUser, "{}");
    }

    private HttpResponse<String> sendResolve(String alertId, @Nullable String grafanaUser, String body) {
        return sendResolveAuthorized(alertId, "Bearer " + VALID_TOKEN, grafanaUser, body);
    }

    private HttpResponse<String> sendResolveAuthorized(String alertId, @Nullable String authorization, @Nullable String grafanaUser, String body) {
        var requestBuilder = HttpRequest.newBuilder()
                                        .uri(URI.create("http://localhost:" + port + "/admin/api/alerts/" + alertId + "/resolve"))
                                        .POST(HttpRequest.BodyPublishers.ofString(body))
                                        .header("Content-Type", "application/json");
        if (authorization != null) {
            requestBuilder.header("Authorization", authorization);
        }
        if (grafanaUser != null) {
            requestBuilder.header("X-Grafana-User", grafanaUser);
        }
        return getAsUnchecked(() -> httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString()));
    }
}
