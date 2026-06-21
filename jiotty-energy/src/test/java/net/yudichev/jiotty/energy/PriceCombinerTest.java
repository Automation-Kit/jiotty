package net.yudichev.jiotty.energy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.MINUTES;
import static org.assertj.core.api.Assertions.assertThat;

class PriceCombinerTest {

    @ParameterizedTest
    @MethodSource
    void combinesProfilesCorrectly(Prices real, Prices predicted, Prices expected) {
        assertThat(PriceCombiner.combine(real, predicted)).isEqualTo(expected);
    }

    static Stream<Arguments> combinesProfilesCorrectly() {
        return Stream.of(
                // Predicted overlaps real by one slot (00:30) and adds two new slots (02:00, 02:30).
                Arguments.of(p("00:00", 4, 0.0, 1.0, 2.0, 3.0), p("00:30", 0, 1.1, 2.1, 3.1, 4.1, 5.1),
                             p("00:00", 4, 0.0, 1.0, 2.0, 3.0, 4.1, 5.1)),
                // Predicted starts strictly before real but extends past real's end; combiner walks back to find the overlap point.
                Arguments.of(p("00:30", 4, 0.0, 1.0, 2.0, 3.0), p("00:00", 0, -0.1, 0.0, 1.1, 2.1, 3.1, 4.1, 5.1),
                             p("00:30", 4, 0.0, 1.0, 2.0, 3.0, 4.1, 5.1)),
                // Predicted starts exactly at real's end (no overlap, contiguous).
                Arguments.of(p("00:00", 4, 0.0, 1.0, 2.0, 3.0), p("02:00", 0, 4.1, 5.1),
                             p("00:00", 4, 0.0, 1.0, 2.0, 3.0, 4.1, 5.1)),
                // Predicted ends before real even starts → predicted is dropped, real returned unchanged.
                Arguments.of(p("03:00", 4, 0.0, 1.0, 2.0, 3.0), p("00:00", 0, 4.1, 5.1),
                             p("03:00", 4, 0.0, 1.0, 2.0, 3.0)),
                // Predicted starts after a gap past real's end (real ends 02:00, predicted starts 03:00) → the windows are disjoint, predicted is dropped.
                Arguments.of(p("00:00", 4, 0.0, 1.0, 2.0, 3.0), p("03:00", 0, 4.1, 5.1),
                             p("00:00", 4, 0.0, 1.0, 2.0, 3.0))
        );
    }

    static Prices p(String start, int idxOfPredictedPriceStart, Double... elements) {
        return new Prices(i(start), new PriceProfile(Math.toIntExact(MINUTES.toSeconds(30)), idxOfPredictedPriceStart, List.of(elements)));
    }

    private static Instant i(String str) {
        return str.length() == 5 ? Instant.parse("2024-01-01T" + str + ":00Z") : Instant.parse("2024-01-01T" + str + "Z");
    }
}
