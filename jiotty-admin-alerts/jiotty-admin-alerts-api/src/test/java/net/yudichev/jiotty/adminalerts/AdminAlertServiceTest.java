package net.yudichev.jiotty.adminalerts;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;

import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAlertServiceTest {
    private static final Logger logger = LogManager.getLogger(AdminAlertServiceTest.class);

    private final TestAdminAlertService service = new TestAdminAlertService();

    @Test
    void alertOnFailure_stageFailed_raisesAlertAtGivenSeverityAndTitle() {
        BiConsumer<String, Throwable> consumer = service.alertOnFailure(AdminAlertSeverity.ERROR, "Listing failed", logger);

        consumer.accept(null, new RuntimeException("boom"));

        assertThat(service.activeAlertsById().values())
                .singleElement()
                .satisfies(alert -> {
                    assertThat(alert.severity()).isEqualTo(AdminAlertSeverity.ERROR);
                    assertThat(alert.title()).isEqualTo("Listing failed");
                    assertThat(service.eventsByAlertId(alert.id()))
                            .singleElement()
                            .satisfies(event -> assertThat(event.description()).contains("boom"));
                });
    }

    @Test
    void alertOnFailure_withDescription_carriesDescriptionPrefixIntoEvent() {
        BiConsumer<String, Throwable> consumer =
                service.alertOnFailure(AdminAlertSeverity.WARNING, "Cleanup failed", logger, "firebaseUid abc-123");

        consumer.accept(null, new RuntimeException("boom"));

        assertThat(service.activeAlertsById().values())
                .singleElement()
                .satisfies(alert -> {
                    assertThat(alert.severity()).isEqualTo(AdminAlertSeverity.WARNING);
                    assertThat(alert.title()).isEqualTo("Cleanup failed");
                    assertThat(service.eventsByAlertId(alert.id()))
                            .singleElement()
                            .satisfies(event -> assertThat(event.description()).contains("firebaseUid abc-123").contains("boom"));
                });
    }

    @Test
    void alertOnFailure_stageSucceeded_raisesNoAlert() {
        BiConsumer<String, Throwable> consumer = service.alertOnFailure(AdminAlertSeverity.ERROR, "Listing failed", logger);

        consumer.accept("result", null);

        assertThat(service.activeAlertsById()).isEmpty();
    }
}
