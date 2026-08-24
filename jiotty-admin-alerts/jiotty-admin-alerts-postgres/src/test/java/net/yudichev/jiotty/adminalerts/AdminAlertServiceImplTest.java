package net.yudichev.jiotty.adminalerts;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.ListenerBackedTaskExceptionHandlerRegistry;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.SingleThreadedSchedulingExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainMigrator;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainServiceImpl;
import net.yudichev.jiotty.persistence.test.EmbeddedPostgresExtension;
import net.yudichev.jiotty.persistence.test.UsingEmbeddedPostgres;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.TimeUnit.SECONDS;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@UsingEmbeddedPostgres
class AdminAlertServiceImplTest {
    private static final Instant T0 = Instant.parse("2026-04-15T10:00:00Z");
    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);
    private static final String CURRENT_PID = String.valueOf(ProcessHandle.current().pid());

    @RegisterExtension
    private static final EmbeddedPostgresExtension postgres = new EmbeddedPostgresExtension();

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private ProgrammableClock clock;
    private SingleThreadedSchedulingExecutor executor;
    private PersistenceDomainServiceImpl domainService;
    private DataSourceFactory dataSourceFactory;
    private AdminAlertServiceImpl service;

    @BeforeEach
    void setUp() {
        setUpService(100, 100);
    }

    @AfterEach
    void tearDown() {
        Closeable.closeIfNotNull(service == null ? null : service::stop, domainService == null ? null : domainService::stop, executor);
    }

    private void setUpService(int maxBundles, int maxEventsPerBundle) {
        Closeable.closeIfNotNull(service == null ? null : service::stop, domainService == null ? null : domainService::stop, executor);
        clock = new ProgrammableClock();
        clock.setTime(T0);
        executor = new SingleThreadedSchedulingExecutor("admin-alerts-test");
        Provider<SchedulingExecutor> executorProvider = () -> executor;
        dataSourceFactory = postgres.dataSourceFactory();
        domainService = new PersistenceDomainServiceImpl(dataSourceFactory, executorProvider, new ListenerBackedTaskExceptionHandlerRegistry());
        domainService.start();
        service = new AdminAlertServiceImpl(dataSourceFactory,
                                            executorProvider,
                                            domainService,
                                            clock,
                                            2,
                                            AdminAlertSchema.DEFAULT_DOMAIN_NAME,
                                            PersistenceDomainMigrator.FAIL_ON_MIGRATION,
                                            maxBundles,
                                            maxEventsPerBundle,
                                            meterRegistry);
        service.start();
    }

    @Test
    void raise_newAlert_insertsBundleWithOneEventAndPidLabel() {
        String key = service.raise(AdminAlertSeverity.ERROR, "Tesla auth failed", "first failure");
        flush();

        AdminAlert alert = service.findByKey(key).orElseThrow();
        assertThat(alert.id()).startsWith("a");
        assertThat(alert.title()).isEqualTo("Tesla auth failed");
        assertThat(alert.severity()).isEqualTo(AdminAlertSeverity.ERROR);
        assertThat(alert.labels()).containsEntry("pid", CURRENT_PID);
        assertThat(alert.firstSeenAt()).isEqualTo(T0);
        assertThat(alert.lastSeenAt()).isEqualTo(T0);
        assertThat(alert.eventCount()).isEqualTo(1);
        assertThat(alert.resolvedAt()).isEmpty();
        assertThat(alert.key()).startsWith("auto:");
        assertThat(selectEvents(alert.id())).satisfiesExactly(event -> {
            assertThat(event.occurredAt()).isEqualTo(T0);
            assertThat(event.description()).isEqualTo("first failure");
        });
    }

    @Test
    void raise_sameKey_appendsEventAndBumpsCounter() {
        AdminAlertData data1 = data("Tesla auth failed", "first failure", AdminAlertSeverity.ERROR, Map.of("category", "Tesla"));
        String firstKey = service.raise(data1);
        flush();
        String firstId = service.findByKey(firstKey).orElseThrow().id();

        clock.setTime(T0.plus(ONE_MINUTE));
        AdminAlertData data2 = data("Tesla auth failed", "second failure", AdminAlertSeverity.ERROR, Map.of("category", "Tesla"));
        String secondKey = service.raise(data2);
        flush();

        assertThat(secondKey).isEqualTo(firstKey);
        AdminAlert alert = service.findByKey(secondKey).orElseThrow();
        assertThat(alert.id()).isEqualTo(firstId);
        assertThat(alert.firstSeenAt()).isEqualTo(T0);
        assertThat(alert.lastSeenAt()).isEqualTo(T0.plus(ONE_MINUTE));
        assertThat(alert.eventCount()).isEqualTo(2);
        assertThat(selectEvents(firstId)).satisfiesExactly(
                event -> {
                    assertThat(event.occurredAt()).isEqualTo(T0);
                    assertThat(event.description()).isEqualTo("first failure");
                },
                event -> {
                    assertThat(event.occurredAt()).isEqualTo(T0.plus(ONE_MINUTE));
                    assertThat(event.description()).isEqualTo("second failure");
                });
    }

    @Test
    void raise_differentLabels_producesDistinctBundles() {
        String key1 = service.raise(data("MQTT down", "d", AdminAlertSeverity.ERROR, Map.of("category", "Tesla")));
        String key2 = service.raise(data("MQTT down", "d", AdminAlertSeverity.ERROR, Map.of("category", "mqtt")));
        flush();

        assertThat(key2).isNotEqualTo(key1);
        AdminAlert a1 = service.findByKey(key1).orElseThrow();
        AdminAlert a2 = service.findByKey(key2).orElseThrow();
        assertThat(a1.id()).isNotEqualTo(a2.id());
    }

    @Test
    void raise_callerSuppliesPidLabel_frameworkValueWins() {
        String key = service.raise(data("title", "d", AdminAlertSeverity.ERROR, Map.of("pid", "999999")));
        flush();

        AdminAlert alert = service.findByKey(key).orElseThrow();
        assertThat(alert.labels()).containsEntry("pid", CURRENT_PID);
    }

    @Test
    void raise_clockAdvancesBeforeTheWriteRuns_recordsTheInstantTheAlertWasRaised() {
        AdminAlertData data = data("title", "ignored", AdminAlertSeverity.ERROR, Map.of());

        clock.setTime(T0);
        String key = service.raise(data.withDescription("e1"));
        clock.setTime(T0.plus(ONE_MINUTE));
        service.raise(data.withDescription("e2"));
        flush();

        AdminAlert alert = service.findByKey(key).orElseThrow();
        assertThat(selectEvents(alert.id()))
                .extracting(EventRow::occurredAt)
                .containsExactly(T0, T0.plus(ONE_MINUTE));
    }

    @Test
    void raise_overMaxEventsPerBundle_dropsOldestEvent() {
        setUpService(100, 3);
        AdminAlertData data = data("title", "ignored", AdminAlertSeverity.ERROR, Map.of());

        clock.setTime(T0);
        String key = service.raise(data.withDescription("e1"));
        clock.setTime(T0.plus(ONE_MINUTE));
        service.raise(data.withDescription("e2"));
        clock.setTime(T0.plus(ONE_MINUTE.multipliedBy(2)));
        service.raise(data.withDescription("e3"));
        clock.setTime(T0.plus(ONE_MINUTE.multipliedBy(3)));
        service.raise(data.withDescription("e4"));
        flush();

        AdminAlert alert = service.findByKey(key).orElseThrow();
        assertThat(alert.eventCount()).isEqualTo(4);
        assertThat(selectEvents(alert.id()))
                .extracting(EventRow::description)
                .containsExactly("e2", "e3", "e4");
    }

    @Test
    void raise_overMaxBundles_dropsOldestBundleAndCascadesEvents() {
        setUpService(2, 100);
        clock.setTime(T0);
        String oldestKey = service.raise(data("title-1", "d1", AdminAlertSeverity.ERROR, Map.of()));
        flush();
        String oldestId = service.findByKey(oldestKey).orElseThrow().id();
        clock.setTime(T0.plus(ONE_MINUTE));
        String middleKey = service.raise(data("title-2", "d2", AdminAlertSeverity.ERROR, Map.of()));
        clock.setTime(T0.plus(ONE_MINUTE.multipliedBy(2)));
        String newestKey = service.raise(data("title-3", "d3", AdminAlertSeverity.ERROR, Map.of()));
        flush();

        assertThat(service.findByKey(oldestKey)).isEmpty();
        assertThat(service.findByKey(middleKey)).isPresent();
        assertThat(service.findByKey(newestKey)).isPresent();
        // FK CASCADE: events for the evicted bundle are gone too.
        assertThat(selectEvents(oldestId)).isEmpty();
    }

    @Test
    void resolve_setsResolvedFieldsAndAllowsReRaiseAsFreshBundle() {
        String firstKey = service.raise(data("title", "first", AdminAlertSeverity.ERROR, Map.of()));
        flush();
        String firstId = service.findByKey(firstKey).orElseThrow().id();

        clock.setTime(T0.plus(ONE_MINUTE));
        Optional<String> resolved = await(service.resolve(firstKey, "no longer firing"));
        assertThat(resolved).contains(firstId);
        AdminAlert resolvedAlert = service.findByKey(firstKey).orElseThrow();
        assertThat(resolvedAlert.resolvedAt()).contains(T0.plus(ONE_MINUTE));
        assertThat(resolvedAlert.resolvedBy()).contains("system");
        assertThat(resolvedAlert.resolutionNote()).contains("no longer firing");

        clock.setTime(T0.plus(ONE_MINUTE.multipliedBy(2)));
        String secondKey = service.raise(data("title", "first", AdminAlertSeverity.ERROR, Map.of()));
        flush();
        assertThat(secondKey).isEqualTo(firstKey);
        String secondId = service.findByKey(secondKey).orElseThrow().id();
        assertThat(secondId).isNotEqualTo(firstId);
    }

    @Test
    void resolve_keepsEventsVisible() {
        String key = service.raise(data("title", "e1", AdminAlertSeverity.ERROR, Map.of()));
        clock.setTime(T0.plus(ONE_MINUTE));
        service.raise(data("title", "e2", AdminAlertSeverity.ERROR, Map.of()));
        flush();
        String id = service.findByKey(key).orElseThrow().id();

        clock.setTime(T0.plus(ONE_MINUTE.multipliedBy(2)));
        await(service.resolve(key, "note"));

        assertThat(selectEvents(id)).extracting(EventRow::description).containsExactly("e1", "e2");
    }

    @Test
    void resolve_alreadyResolved_returnsEmpty() {
        String key = service.raise(data("title", "desc", AdminAlertSeverity.ERROR, Map.of()));
        flush();
        await(service.resolve(key, "note"));

        Optional<String> second = await(service.resolve(key, "note"));

        assertThat(second).isEmpty();
    }

    @Test
    void resolveById_unknown_returnsUnknownOutcome() {
        assertThat(await(service.resolveById("a-missing", "alice@example.com", Optional.empty())))
                .isEqualTo(AdminAlertService.ResolveByIdOutcome.UNKNOWN);
    }

    @Test
    void resolveById_alreadyResolved_returnsAlreadyResolvedOutcome() {
        String key = service.raise(data("title", "desc", AdminAlertSeverity.ERROR, Map.of()));
        flush();
        String id = service.findByKey(key).orElseThrow().id();
        await(service.resolve(key, "note"));

        assertThat(await(service.resolveById(id, "alice@example.com", Optional.empty())))
                .isEqualTo(AdminAlertService.ResolveByIdOutcome.ALREADY_RESOLVED);
    }

    @Test
    void resolveById_active_marksResolvedWithProvidedFields() {
        String key = service.raise(data("title", "desc", AdminAlertSeverity.ERROR, Map.of()));
        flush();
        String id = service.findByKey(key).orElseThrow().id();

        clock.setTime(T0.plus(ONE_MINUTE));
        assertThat(await(service.resolveById(id, "alice@example.com", Optional.of("manual"))))
                .isEqualTo(AdminAlertService.ResolveByIdOutcome.RESOLVED);

        AdminAlert alert = service.findByKey(key).orElseThrow();
        assertThat(alert.resolvedAt()).contains(T0.plus(ONE_MINUTE));
        assertThat(alert.resolvedBy()).contains("alice@example.com");
        assertThat(alert.resolutionNote()).contains("manual");
    }

    @Test
    void deleteResolvedOlderThan_deletesOnlyOldResolvedRowsAndCascadesEvents() {
        String activeKey = service.raise(data("active", "d", AdminAlertSeverity.ERROR, Map.of("category", "active")));
        String recentKey = service.raise(data("recent", "d", AdminAlertSeverity.ERROR, Map.of("category", "recent")));
        String oldKey = service.raise(data("old", "d", AdminAlertSeverity.ERROR, Map.of("category", "old")));
        flush();
        String resolvedOldId = service.findByKey(oldKey).orElseThrow().id();

        clock.setTime(T0.plus(Duration.ofDays(10)));
        await(service.resolve(oldKey, "note"));
        clock.setTime(T0.plus(Duration.ofDays(200)));
        await(service.resolve(recentKey, "note"));

        clock.setTime(T0.plus(Duration.ofDays(201)));
        Integer deleted = await(service.deleteResolvedOlderThan(Duration.ofDays(180)));

        assertThat(deleted).isEqualTo(1);
        assertThat(service.findByKey(oldKey)).isEmpty();
        assertThat(service.findByKey(recentKey)).isPresent();
        assertThat(service.findByKey(activeKey)).isPresent();
        assertThat(selectEvents(resolvedOldId)).isEmpty();
    }

    @Test
    void deleteResolvedOlderThan_zeroOrNegative_throws() {
        assertThatThrownBy(() -> service.deleteResolvedOlderThan(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.deleteResolvedOlderThan(Duration.ofMinutes(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(AdminAlertSeverity.class)
    void severityRoundTripsThroughEnumColumn(AdminAlertSeverity severity) {
        AdminAlertData data = data("title-" + severity.name(), "d", severity, Map.of("category", "test"));
        String key = service.raise(data);
        flush();

        AdminAlert alert = service.findByKey(key).orElseThrow();
        assertThat(alert.severity()).isEqualTo(severity);
    }

    @Test
    void rejectsInvalidSchemaVersion() {
        assertThatThrownBy(() -> new AdminAlertServiceImpl(postgres.dataSourceFactory(),
                                                           () -> executor,
                                                           domainService,
                                                           clock,
                                                           0,
                                                           AdminAlertSchema.DEFAULT_DOMAIN_NAME,
                                                           PersistenceDomainMigrator.FAIL_ON_MIGRATION,
                                                           100,
                                                           100,
                                                           meterRegistry))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidCaps() {
        assertThatThrownBy(() -> new AdminAlertServiceImpl(postgres.dataSourceFactory(),
                                                           () -> executor,
                                                           domainService,
                                                           clock,
                                                           2,
                                                           AdminAlertSchema.DEFAULT_DOMAIN_NAME,
                                                           PersistenceDomainMigrator.FAIL_ON_MIGRATION,
                                                           0,
                                                           100,
                                                           meterRegistry))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdminAlertServiceImpl(postgres.dataSourceFactory(),
                                                           () -> executor,
                                                           domainService,
                                                           clock,
                                                           2,
                                                           AdminAlertSchema.DEFAULT_DOMAIN_NAME,
                                                           PersistenceDomainMigrator.FAIL_ON_MIGRATION,
                                                           100,
                                                           0,
                                                           meterRegistry))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void flush() {
        getAsUnchecked(() -> executor.submit(() -> {}).get(10, SECONDS));
    }

    private List<EventRow> selectEvents(String alertId) {
        var rows = new ArrayList<EventRow>();
        try (Connection connection = dataSourceFactory.create().getConnection();
             PreparedStatement stmt = connection.prepareStatement(
                     "SELECT occurred_at, description FROM admin_alerts_alert_event WHERE alert_id = ? ORDER BY occurred_at ASC, id ASC")) {
            stmt.setString(1, alertId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rows.add(new EventRow(rs.getTimestamp(1).toInstant(), rs.getString(2)));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return rows;
    }

    /// Callers raise from catch blocks that must go on to complete a request or rethrow, so a stopped service answers with the same key it would have returned
    /// while running, and counts the loss. See [AdminAlertService#raise]'s `@implSpec`.
    @Test
    void raiseOnAStoppedServiceReturnsTheKeyAndCountsTheLoss() {
        AdminAlertData alertData = data("Gone", "d", AdminAlertSeverity.ERROR, Map.of());
        String keyWhileStarted = service.raise(alertData);
        flush();

        // This test owns the stop, so it clears the field tearDown would stop again.
        AdminAlertServiceImpl stopped = service;
        service = null;
        stopped.stop();

        assertThat(stopped.raise(alertData)).isEqualTo(keyWhileStarted);
        assertThat(meterRegistry.get("admin_alert_raise_failures_total").counter().count()).isEqualTo(1.0);
    }

    private static <T> T await(CompletableFuture<T> future) {
        return getAsUnchecked(() -> future.get(10, SECONDS));
    }

    private static AdminAlertData data(String title, String description, AdminAlertSeverity severity, Map<String, String> labels) {
        return AdminAlertData.builder()
                             .setTitle(title)
                             .setDescription(description)
                             .setSeverity(severity)
                             .setLabels(labels)
                             .build();
    }

    private record EventRow(Instant occurredAt, String description) {}
}
