package net.yudichev.jiotty.adminalerts;

import net.yudichev.jiotty.common.testutil.LogCapture;
import net.yudichev.jiotty.common.testutil.LogCapture.CapturedLine;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class LoggingAdminAlertServiceTest {
    private final LoggingAdminAlertService service = new LoggingAdminAlertService();

    @Test
    void raise_returnsKeyForRaisedAlert() {
        AdminAlertData data = AdminAlertData.builder()
                                            .setSeverity(AdminAlertSeverity.WARNING)
                                            .setTitle("Cleanup failed")
                                            .setDescription("disk full")
                                            .build();

        String key = service.raise(data);

        assertThat(key).isEqualTo(data.key());
    }

    @Test
    void resolve_logsTheResolutionOfAnActiveAlertAtItsSeverity() {
        String key = raiseSyncFailure();

        try (LogCapture log = LogCapture.capture(LoggingAdminAlertService.class, Level.INFO)) {
            service.resolve(key, "recovered");

            assertThat(log.lines()).extracting(CapturedLine::level, CapturedLine::message)
                                   .containsExactly(tuple(Level.ERROR, "ALERT RESOLVED " + key + ": recovered"));
        }
    }

    @Test
    void resolve_isIdempotent_secondResolveLogsNothing() {
        String key = raiseSyncFailure();
        service.resolve(key, "recovered");

        try (LogCapture log = LogCapture.capture(LoggingAdminAlertService.class, Level.INFO)) {
            service.resolve(key, "recovered again");

            assertThat(log.lines()).isEmpty();
        }
    }

    @Test
    void resolve_unknownKeyLogsNothing() {
        try (LogCapture log = LogCapture.capture(LoggingAdminAlertService.class, Level.INFO)) {
            service.resolve("auto:never-raised", "note");

            assertThat(log.lines()).isEmpty();
        }
    }

    private String raiseSyncFailure() {
        return service.raise(AdminAlertData.builder()
                                           .setSeverity(AdminAlertSeverity.ERROR)
                                           .setTitle("Sync failed")
                                           .setDescription("timeout")
                                           .build());
    }

    @Test
    void resolveById_alwaysUnknown() {
        assertThat(service.resolveById("some-id", "operator", Optional.of("note")))
                .isCompletedWithValue(AdminAlertService.ResolveByIdOutcome.UNKNOWN);
    }

    @Test
    void deleteResolvedOlderThan_returnsZero() {
        assertThat(service.deleteResolvedOlderThan(Duration.ofDays(30))).isCompletedWithValue(0);
    }

    @Test
    void deleteByLabel_returnsZero() {
        assertThat(service.deleteByLabel("userId", "user-1")).isCompletedWithValue(0);
    }
}
