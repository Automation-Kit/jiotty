package net.yudichev.jiotty.user.ui.io;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static net.yudichev.jiotty.common.rest.HttpStatuses.INTERNAL_SERVER_ERROR_500;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeadlineTest {
    private static final Answer ANSWER = Answer.of(INTERNAL_SERVER_ERROR_500);

    /// Only the server keeping an exchange waiting is worth an alert: a client trickling its body is something any caller can do.
    @Test
    void onlyTheServerStallingIsTheServers() {
        assertThat(Deadline.Stall.HANDLING.serverStalled()).isTrue();
        assertThat(Deadline.Stall.RECEIVING_BODY.serverStalled()).isFalse();
    }

    @Test
    void refusesATimeoutThatIsNotPositive() {
        assertThatThrownBy(() -> Deadline.after(Duration.ZERO, ANSWER, _ -> {}, _ -> {})).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noneIsNoDeadlineAtAll() {
        assertThat(Deadline.none()).isNotInstanceOf(Deadline.After.class);
    }
}
