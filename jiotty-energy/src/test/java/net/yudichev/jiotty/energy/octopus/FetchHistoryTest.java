package net.yudichev.jiotty.energy.octopus;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FetchHistoryTest {
    private static final Instant NOW = Instant.parse("2024-01-01T10:00:00Z");
    private static final Duration SPACING = Duration.ofMinutes(15);

    private final FetchHistory history = new FetchHistory();

    @Test
    void noFetchStarted_allowsAFetchNow() {
        assertThat(history.earliestNextStart(NOW, SPACING)).isEqualTo(NOW);
    }

    @Test
    void recentFetch_spacesTheNextOne() {
        history.recordStart(NOW.minus(SPACING.dividedBy(3)));

        assertThat(history.earliestNextStart(NOW, SPACING)).isEqualTo(NOW.minus(SPACING.dividedBy(3)).plus(SPACING));
    }

    @Test
    void fetchLongerAgoThanTheSpacing_allowsAFetchNow() {
        history.recordStart(NOW.minus(SPACING.multipliedBy(2)));

        assertThat(history.earliestNextStart(NOW, SPACING)).isEqualTo(NOW);
    }
}
