package net.yudichev.jiotty.adminalerts;

import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.SingleThreadedSchedulingExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainMigrator;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainServiceImpl;
import net.yudichev.jiotty.persistence.test.EmbeddedPostgresExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.TimeUnit.SECONDS;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminAlertServiceImplTest {
    private static final Instant T0 = Instant.parse("2026-04-15T10:00:00Z");
    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);

    @RegisterExtension
    private static final EmbeddedPostgresExtension postgres = new EmbeddedPostgresExtension();

    private ProgrammableClock clock;
    private SingleThreadedSchedulingExecutor executor;
    private PersistenceDomainServiceImpl domainService;
    private AdminAlertServiceImpl service;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        clock.setTime(T0);
        executor = new SingleThreadedSchedulingExecutor("admin-alerts-test");
        Provider<SchedulingExecutor> executorProvider = () -> executor;
        DataSourceFactory dataSourceFactory = postgres.dataSourceFactory();
        domainService = new PersistenceDomainServiceImpl(dataSourceFactory, executorProvider);
        domainService.start();
        service = new AdminAlertServiceImpl(dataSourceFactory,
                                            executorProvider,
                                            domainService,
                                            clock,
                                            1,
                                            AdminAlertSchema.DEFAULT_DOMAIN_NAME,
                                            PersistenceDomainMigrator.FAIL_ON_MIGRATION);
        service.start();
    }

    @AfterEach
    void tearDown() {
        Closeable.closeIfNotNull(service == null ? null : service::stop, domainService == null ? null : domainService::stop, executor);
    }

    @Test
    void raise_newDedupKey_insertsRowWithReturnedId() {
        String id = await(service.raise(data("k-new", "Tesla auth failed", "first failure")));

        assertThat(id).startsWith("a");
        Optional<AdminAlert> alert = await(service.getById(id));
        assertThat(alert).hasValueSatisfying(a -> {
            assertThat(a.dedupKey()).isEqualTo("k-new");
            assertThat(a.title()).isEqualTo("Tesla auth failed");
            assertThat(a.description()).isEqualTo("first failure");
            assertThat(a.severity()).isEqualTo(AdminAlertSeverity.ERROR);
            assertThat(a.labels()).containsEntry("category", "my-category");
            assertThat(a.firstSeenAt()).isEqualTo(T0);
            assertThat(a.lastSeenAt()).isEqualTo(T0);
            assertThat(a.updateCount()).isEqualTo(1);
            assertThat(a.resolvedAt()).isEmpty();
        });
    }

    @Test
    void raise_sameDedupKeyWhileActive_bumpsHeartbeatLeavesContentUntouched() {
        String firstId = await(service.raise(data("k1", "Tesla auth failed", "first failure")));

        clock.setTime(T0.plus(ONE_MINUTE));
        String secondId = await(service.raise(data("k1", "ignored title", "ignored description")));

        assertThat(secondId).isEqualTo(firstId);
        Optional<AdminAlert> alert = await(service.getById(firstId));
        assertThat(alert).hasValueSatisfying(a -> {
            assertThat(a.title()).isEqualTo("Tesla auth failed");
            assertThat(a.description()).isEqualTo("first failure");
            assertThat(a.firstSeenAt()).isEqualTo(T0);
            assertThat(a.lastSeenAt()).isEqualTo(T0.plus(ONE_MINUTE));
            assertThat(a.updateCount()).isEqualTo(2);
        });
    }

    @Test
    void update_descriptionOnly_replacesDescriptionAndBumpsHeartbeat() {
        String id = await(service.raise(data("k1", "title", "old description")));

        clock.setTime(T0.plus(ONE_MINUTE));
        Optional<String> updated = await(service.update("k1", AdminAlertUpdate.builder().setDescription("new description").build()));

        assertThat(updated).contains(id);
        Optional<AdminAlert> alert = await(service.getById(id));
        assertThat(alert).hasValueSatisfying(a -> {
            assertThat(a.description()).isEqualTo("new description");
            assertThat(a.labels()).containsEntry("category", "my-category");
            assertThat(a.lastSeenAt()).isEqualTo(T0.plus(ONE_MINUTE));
            assertThat(a.updateCount()).isEqualTo(2);
        });
    }

    @Test
    void update_labelsOnly_replacesLabelsAndKeepsDescription() {
        String id = await(service.raise(data("k1", "title", "desc")));

        Optional<String> updated = await(service.update("k1",
                                                        AdminAlertUpdate.builder()
                                                                        .setLabels(Map.of("category", "mqtt", "vehicle", "v1"))
                                                                        .build()));

        assertThat(updated).contains(id);
        AdminAlert alert = await(service.getById(id)).orElseThrow();
        assertThat(alert.description()).isEqualTo("desc");
        assertThat(alert.labels()).isEqualTo(Map.of("category", "mqtt", "vehicle", "v1"));
    }

    @Test
    void update_unknownActiveAlert_returnsEmpty() {
        Optional<String> result = await(service.update("missing", AdminAlertUpdate.builder().setDescription("x").build()));

        assertThat(result).isEmpty();
    }

    @Test
    void resolve_setsResolvedFieldsAndAllowsReRaiseAsFreshRow() {
        String firstId = await(service.raise(data("k1", "title", "desc")));

        clock.setTime(T0.plus(ONE_MINUTE));
        Optional<String> resolved = await(service.resolve("k1", Optional.of("no longer firing")));
        assertThat(resolved).contains(firstId);
        AdminAlert resolvedAlert = await(service.getById(firstId)).orElseThrow();
        assertThat(resolvedAlert.resolvedAt()).contains(T0.plus(ONE_MINUTE));
        assertThat(resolvedAlert.resolvedBy()).contains("system");
        assertThat(resolvedAlert.resolutionNote()).contains("no longer firing");

        clock.setTime(T0.plus(ONE_MINUTE).plus(ONE_MINUTE));
        String secondId = await(service.raise(data("k1", "title", "desc")));
        assertThat(secondId).isNotEqualTo(firstId);
    }

    @Test
    void resolve_alreadyResolved_returnsEmpty() {
        await(service.raise(data("k1", "title", "desc")));
        await(service.resolve("k1", Optional.empty()));

        Optional<String> second = await(service.resolve("k1", Optional.empty()));

        assertThat(second).isEmpty();
    }

    @Test
    void resolveById_unknown_returnsUnknownOutcome() {
        assertThat(await(service.resolveById("a-missing", "alice@example.com", Optional.empty())))
                .isEqualTo(AdminAlertService.ResolveByIdOutcome.UNKNOWN);
    }

    @Test
    void resolveById_alreadyResolved_returnsAlreadyResolvedOutcome() {
        String id = await(service.raise(data("k1", "title", "desc")));
        await(service.resolve("k1", Optional.empty()));

        assertThat(await(service.resolveById(id, "alice@example.com", Optional.empty())))
                .isEqualTo(AdminAlertService.ResolveByIdOutcome.ALREADY_RESOLVED);
    }

    @Test
    void resolveById_active_marksResolvedWithProvidedFields() {
        String id = await(service.raise(data("k1", "title", "desc")));

        clock.setTime(T0.plus(ONE_MINUTE));
        assertThat(await(service.resolveById(id, "alice@example.com", Optional.of("manual"))))
                .isEqualTo(AdminAlertService.ResolveByIdOutcome.RESOLVED);

        AdminAlert alert = await(service.getById(id)).orElseThrow();
        assertThat(alert.resolvedAt()).contains(T0.plus(ONE_MINUTE));
        assertThat(alert.resolvedBy()).contains("alice@example.com");
        assertThat(alert.resolutionNote()).contains("manual");
    }

    @Test
    void deleteResolvedOlderThan_deletesOnlyOldResolvedRows() {
        String activeId = await(service.raise(data("k-active", "active title", "desc")));
        String resolvedRecentId = await(service.raise(data("k-recent", "recent", "desc")));
        String resolvedOldId = await(service.raise(data("k-old", "old", "desc")));

        clock.setTime(T0.plus(Duration.ofDays(10)));
        await(service.resolve("k-old", Optional.empty()));
        clock.setTime(T0.plus(Duration.ofDays(200)));
        await(service.resolve("k-recent", Optional.empty()));

        clock.setTime(T0.plus(Duration.ofDays(201)));
        Integer deleted = await(service.deleteResolvedOlderThan(Duration.ofDays(180)));

        assertThat(deleted).isEqualTo(1);
        assertThat(await(service.getById(resolvedOldId))).isEmpty();
        assertThat(await(service.getById(resolvedRecentId))).isPresent();
        assertThat(await(service.getById(activeId))).isPresent();
    }

    @Test
    void deleteResolvedOlderThan_zeroOrNegative_throws() {
        assertThatThrownBy(() -> service.deleteResolvedOlderThan(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.deleteResolvedOlderThan(Duration.ofMinutes(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(AdminAlertSeverity.class)
    void severityRoundTripsThroughEnumColumn(AdminAlertSeverity severity) {
        var data = new AdminAlertData("k-" + severity.name(), "t", "d", severity, Map.of("category", "test"));
        String id = await(service.raise(data));

        AdminAlert alert = await(service.getById(id)).orElseThrow();
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
                                                           PersistenceDomainMigrator.FAIL_ON_MIGRATION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static <T> T await(CompletableFuture<T> future) {
        return getAsUnchecked(() -> future.get(10, SECONDS));
    }

    private static AdminAlertData data(String dedupKey, String title, String description) {
        return new AdminAlertData(dedupKey, title, description, AdminAlertSeverity.ERROR, Map.of("category", "my-category"));
    }
}
