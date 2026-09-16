package net.yudichev.jiotty.adminalerts;

import com.google.common.collect.ImmutableMap;
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

    /// The labels are the point of this overload: they let a caller find the alert again — or delete it — once the subject it names is gone, which a title
    /// alone cannot do.
    @Test
    void raise_withLabels_carriesThemOntoTheAlertAndKeepsTheSubjectOutOfTheTitle() {
        service.raise(AdminAlertSeverity.WARNING, "Expiring an idle account failed", logger, "the sweep gave up",
                      new RuntimeException("boom"), ImmutableMap.of("userId", "u1"));

        assertThat(service.activeAlertsById().values())
                .singleElement()
                .satisfies(alert -> {
                    assertThat(alert.severity()).isEqualTo(AdminAlertSeverity.WARNING);
                    assertThat(alert.title()).isEqualTo("Expiring an idle account failed").doesNotContain("u1");
                    assertThat(alert.labels()).containsExactlyEntriesOf(ImmutableMap.of("userId", "u1"));
                    assertThat(service.eventsByAlertId(alert.id()))
                            .singleElement()
                            .satisfies(event -> assertThat(event.description()).startsWith("the sweep gave up: ").contains("boom").doesNotContain("u1"));
                });
    }

    /// Repeats for one subject under one title are one bundle, so a retry loop does not become a row per attempt on the operator's dashboard.
    @Test
    void raise_withLabels_repeatsDedupeIntoOneAlert() {
        service.raise(AdminAlertSeverity.WARNING, "Title", logger, "first", new RuntimeException("one"), ImmutableMap.of("userId", "u1"));
        service.raise(AdminAlertSeverity.WARNING, "Title", logger, "second", new RuntimeException("two"), ImmutableMap.of("userId", "u1"));

        assertThat(service.activeAlertsById()).hasSize(1);
        assertThat(service.eventsByAlertId(service.activeAlertsById().keySet().iterator().next())).hasSize(2);
    }

    /// A different subject under the same title is a different bundle, because the labels participate in the key — otherwise one account's failures would
    /// resolve another's.
    @Test
    void raise_withLabels_differentSubjectsAreSeparateAlerts() {
        service.raise(AdminAlertSeverity.WARNING, "Title", logger, "failed", new RuntimeException("one"), ImmutableMap.of("userId", "u1"));
        service.raise(AdminAlertSeverity.WARNING, "Title", logger, "failed", new RuntimeException("two"), ImmutableMap.of("userId", "u2"));

        assertThat(service.activeAlertsById()).hasSize(2);
    }
}
