package net.yudichev.jiotty.user.ui;

import net.yudichev.jiotty.adminalerts.TestAdminAlertService;
import net.yudichev.jiotty.user.ui.io.Deadline;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HandlerDeadlinesTest {
    private static final String ALERT_TITLE = "Test request timed out";
    private static final String SUBJECT_ID = "user-1";

    private final TestAdminAlertService alertService = new TestAdminAlertService();
    private final Deadline.After deadline =
            (Deadline.After) HandlerDeadlines.create(alertService, LogManager.getLogger(HandlerDeadlinesTest.class), SUBJECT_ID, ALERT_TITLE, _ -> {});

    @Test
    void answersAServerFaultAfterTheTimeout() {
        assertThat(deadline.timeout()).isEqualTo(HandlerDeadlines.TIMEOUT);
        assertThat(deadline.answer()).isSameAs(ErrorResponse.SERVER_FAULT);
    }

    /// The alert's description starts with the subject id, since the container thread raising it carries no request context.
    @Test
    void alertsOnAHandlerThatHasNotAnswered() {
        deadline.onExpired().accept(Deadline.Stall.HANDLING);

        assertThat(alertService.activeAlertsById().entrySet())
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.getValue().title()).isEqualTo(ALERT_TITLE);
                    assertThat(alertService.eventsByAlertId(entry.getKey()))
                            .singleElement()
                            .satisfies(event -> assertThat(event.description()).startsWith('[' + SUBJECT_ID + ']'));
                });
    }

    /// A client trickling its body is its own slowness, which any caller can cause, so it must not be able to flood the operator with alerts.
    @Test
    void raisesNoAlertForABodyStillArriving() {
        deadline.onExpired().accept(Deadline.Stall.RECEIVING_BODY);

        assertThat(alertService.activeAlertsById()).isEmpty();
    }
}
