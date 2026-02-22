package net.yudichev.jiotty.common.misc;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UniqueIdTest {
    @Test
    void generateReturnsFixedLengthIdWithExpectedCharacters() {
        char prefix = 'u';
        String id = UniqueId.generate(prefix);

        assertThat(id).hasSize(UniqueId.TOTAL_LENGTH);
        assertThat(id.charAt(0)).isEqualTo(prefix);
        for (int index = 1; index <= UniqueId.TIME_DIGITS; index++) {
            assertThat(Character.isDigit(id.charAt(index))).isTrue();
        }
        for (int index = UniqueId.TIME_DIGITS + 1; index < id.length(); index++) {
            assertThat(isLowercaseAlphaNumeric(id.charAt(index))).isTrue();
        }
    }

    @Test
    void timeComponentReflectsCurrentTime() {
        Instant before = Instant.now();
        String id = UniqueId.generate('t');
        Instant after = Instant.now();

        long lowerBound = microsSinceEpoch(before);
        long upperBound = microsSinceEpoch(after);
        long timeComponent = Long.parseLong(id.substring(1, UniqueId.TIME_DIGITS + 1));

        assertThat(timeComponent).isGreaterThanOrEqualTo(lowerBound);
        assertThat(timeComponent).isLessThanOrEqualTo(upperBound);
    }

    private static long microsSinceEpoch(Instant instant) {
        long microsSinceEpoch = (instant.getEpochSecond() - UniqueId.EPOCH_SECONDS) * UniqueId.MICROS_PER_SECOND
                                + instant.getNano() / UniqueId.NANOS_PER_MICRO;
        return Math.max(microsSinceEpoch, 0);
    }

    private static boolean isLowercaseAlphaNumeric(char value) {
        return value >= '0' && value <= '9' || value >= 'a' && value <= 'z';
    }
}
