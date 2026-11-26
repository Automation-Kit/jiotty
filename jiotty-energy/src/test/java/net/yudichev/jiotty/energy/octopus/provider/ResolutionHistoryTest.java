package net.yudichev.jiotty.energy.octopus.provider;

import net.yudichev.jiotty.energy.octopus.provider.ResolutionHistory.FailedResolution;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ResolutionHistoryTest {
    private static final String TARIFF = "E-1R-VAR-22-11-01-A";
    private static final FailedResolution FAILED_RESOLUTION = new FailedResolution(Instant.parse("2024-01-01T10:00:00Z"), new RuntimeException("down"));

    private final ResolutionHistory history = new ResolutionHistory();

    @Test
    void recordedFailure_isReturnedUntilASuccess() {
        assertThat(history.failedResolution(TARIFF)).isEmpty();

        history.recordFailure(TARIFF, FAILED_RESOLUTION);
        assertThat(history.failedResolution(TARIFF)).hasValue(FAILED_RESOLUTION);

        history.recordSuccess(TARIFF);
        assertThat(history.failedResolution(TARIFF)).isEmpty();
    }
}
