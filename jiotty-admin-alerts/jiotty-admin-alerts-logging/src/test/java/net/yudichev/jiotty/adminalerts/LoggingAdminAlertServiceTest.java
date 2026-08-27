package net.yudichev.jiotty.adminalerts;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

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
    void resolve_returnsKeyWhenAlertActive() {
        String key = service.raise(AdminAlertData.builder()
                                                 .setSeverity(AdminAlertSeverity.ERROR)
                                                 .setTitle("Sync failed")
                                                 .setDescription("timeout")
                                                 .build());

        assertThat(service.resolve(key, "recovered")).isCompletedWithValue(Optional.of(key));
    }

    @Test
    void resolve_isIdempotent_secondResolveReturnsEmpty() {
        String key = service.raise(AdminAlertData.builder()
                                                 .setSeverity(AdminAlertSeverity.ERROR)
                                                 .setTitle("Sync failed")
                                                 .setDescription("timeout")
                                                 .build());

        assertThat(service.resolve(key, "recovered")).isCompletedWithValue(Optional.of(key));
        assertThat(service.resolve(key, "recovered again")).isCompletedWithValue(Optional.empty());
    }

    @Test
    void resolve_unknownKeyReturnsEmpty() {
        assertThat(service.resolve("auto:never-raised", "note")).isCompletedWithValue(Optional.empty());
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
