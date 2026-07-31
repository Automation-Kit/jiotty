package net.yudichev.jiotty.adminalerts;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SharedConditionAdminAlerterTest {
    private static final Logger logger = LogManager.getLogger(SharedConditionAdminAlerterTest.class);

    private final TestAdminAlertService service = new TestAdminAlertService();
    /// Deterministic executor: transitions queue on the clock and run, in submission order, on [ProgrammableClock#tick()].
    private final ProgrammableClock clock = new ProgrammableClock();
    private final SharedConditionAdminAlerter alert =
            new SharedConditionAdminAlerter(service, AdminAlertSeverity.WARNING, "Upstream unavailable",
                                            clock.createSingleThreadedSchedulingExecutor("alert-state"));

    @Test
    void reportInterleavedWithAnotherSubjectsResolve_leavesTheConditionAlerted() {
        alert.reportFailure("user1", logger, new RuntimeException("boom"));
        // user2 reports while user1's clear sits between deciding to resolve and resolving. The condition still holds for user2, so it must stay alerted.
        service.runBeforeNextResolve(() -> alert.reportFailure("user2", logger, new RuntimeException("boom")));

        alert.clear("user1");

        clock.tick();
        assertThat(service.activeAlertsById()).describedAs("user2 is still failing, so the shared condition must remain alerted").hasSize(1);
    }

    @Test
    void firstFailure_raisesSingleAlertWithTitleSeverityAndCause() {
        alert.reportFailure("user1", logger, new RuntimeException("boom"));

        clock.tick();
        assertThat(service.activeAlertsById().values())
                .singleElement()
                .satisfies(bundle -> {
                    assertThat(bundle.severity()).isEqualTo(AdminAlertSeverity.WARNING);
                    assertThat(bundle.title()).isEqualTo("Upstream unavailable");
                    assertThat(service.eventsByAlertId(bundle.id()))
                            .singleElement()
                            .satisfies(event -> assertThat(event.description()).contains("boom"));
                });
    }

    @Test
    void repeatedFailureOfSameSubjectWithSameCause_addsNoEvent() {
        alert.reportFailure("user1", logger, new RuntimeException("boom"));
        alert.reportFailure("user1", logger, new RuntimeException("boom"));

        clock.tick();
        assertThat(service.activeAlertsById().values())
                .singleElement()
                .satisfies(bundle -> assertThat(service.eventsByAlertId(bundle.id())).hasSize(1));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 10, 1000})
    void manySubjectsSharingOneCause_addOneEventCarryingThatCause(int subjectCount) {
        for (int i = 1; i <= subjectCount; i++) {
            alert.reportFailure("user" + i, logger, new RuntimeException("boom"));
        }

        clock.tick();
        assertThat(service.activeAlertsById().values())
                .singleElement()
                .satisfies(bundle -> assertThat(service.eventsByAlertId(bundle.id()))
                        .describedAs("one event per distinct cause footprint, however many subjects report it")
                        .singleElement()
                        .satisfies(event -> assertThat(event.description()).contains("boom")));
    }

    @Test
    void distinctCauses_eachAddTheirOwnEvent() {
        alert.reportFailure("user1", logger, new RuntimeException("gateway down"));
        alert.reportFailure("user2", logger, new RuntimeException("gateway down"));
        alert.reportFailure("user3", logger, new RuntimeException("read timeout"));

        clock.tick();
        assertThat(service.activeAlertsById().values())
                .singleElement()
                .satisfies(bundle -> assertThat(service.eventsByAlertId(bundle.id()))
                        .extracting(TestAdminAlertService.TestEvent::description)
                        .satisfiesExactly(d -> assertThat(d).contains("gateway down"),
                                          d -> assertThat(d).contains("read timeout")));
    }

    @Test
    void causeRecurringAfterResolution_addsAnEventToTheFreshBundle() {
        alert.reportFailure("user1", logger, new RuntimeException("boom"));
        alert.clear("user1");

        alert.reportFailure("user1", logger, new RuntimeException("boom"));

        clock.tick();
        assertThat(service.activeAlertsById().values())
                .singleElement()
                .satisfies(bundle -> assertThat(service.eventsByAlertId(bundle.id()))
                        .describedAs("footprints are forgotten on resolution, so the same cause reopens a bundle with its own event")
                        .singleElement()
                        .satisfies(event -> assertThat(event.description()).contains("boom")));
    }

    @Test
    void clearOfOneOfTwoFailingSubjects_keepsAlertActive() {
        alert.reportFailure("user1", logger, new RuntimeException("boom"));
        alert.reportFailure("user2", logger, new RuntimeException("boom"));

        alert.clear("user1");

        clock.tick();
        assertThat(service.activeAlertsById()).hasSize(1);
    }

    @Test
    void clearOfLastFailingSubject_resolvesAlert() {
        alert.reportFailure("user1", logger, new RuntimeException("boom"));
        alert.reportFailure("user2", logger, new RuntimeException("boom"));

        alert.clear("user1");
        alert.clear("user2");

        clock.tick();
        assertThat(service.activeAlertsById()).isEmpty();
        assertThat(service.resolvedAlertsById().values())
                .singleElement()
                .satisfies(bundle -> assertThat(bundle.resolutionNote()).contains("all reporting subjects recovered"));
    }

    @Test
    void clearOfSubjectThatNeverFailed_isNoOp() {
        alert.reportFailure("user1", logger, new RuntimeException("boom"));

        alert.clear("user2");

        clock.tick();
        assertThat(service.activeAlertsById()).hasSize(1);
    }

    @Test
    void clearWhenNothingFailing_isNoOp() {
        alert.clear("user1");

        clock.tick();
        assertThat(service.alertsById()).isEmpty();
    }

    @Test
    void failureAfterResolution_raisesFreshActiveAlert() {
        alert.reportFailure("user1", logger, new RuntimeException("boom"));
        alert.clear("user1");

        alert.reportFailure("user2", logger, new RuntimeException("boom again"));

        clock.tick();
        assertThat(service.resolvedAlertsById()).hasSize(1);
        assertThat(service.activeAlertsById()).hasSize(1);
    }
}
