package net.yudichev.jiotty.user.ui.sse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UnlimitedSseAdmissionTest {
    @Test
    void theUnlimitedBudgetAdmitsEveryStream() {
        SseAdmission admission = SseAdmission.unlimited();

        for (int i = 0; i < 1_000; i++) {
            assertThat(admission.tryAdmit()).isTrue();
        }
        admission.release();
        admission.recordChannelFull();
        admission.recordSlowReaderDropped();
    }
}
