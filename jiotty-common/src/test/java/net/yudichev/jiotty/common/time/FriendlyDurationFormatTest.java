package net.yudichev.jiotty.common.time;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FriendlyDurationFormatTest {

    @ParameterizedTest
    @MethodSource
    void parsesValid(String input, Duration expected) {
        assertThat(FriendlyDurationFormat.parseHuman(input)).isEqualTo(expected);
    }

    // -------------------- Parsing: valid inputs --------------------
    static Stream<Arguments> parsesValid() {
        return Stream.of(
                // clock forms
                Arguments.of("02:30", Duration.ofHours(2).plusMinutes(30)),
                Arguments.of("12:05:10", Duration.ofHours(12).plusMinutes(5).plusSeconds(10)),
                // days + clock
                Arguments.of("1d 02:30", Duration.ofDays(1).plusHours(2).plusMinutes(30)),
                Arguments.of("2d 00:00:15", Duration.ofDays(2).plusSeconds(15)),
                Arguments.of("1d02:30", Duration.ofDays(1).plusHours(2).plusMinutes(30)),
                // unit tokens
                Arguments.of("2h 30m", Duration.ofHours(2).plusMinutes(30)),
                Arguments.of("90m", Duration.ofMinutes(90)),
                Arguments.of("3600s", Duration.ofSeconds(3600)),
                Arguments.of("1d 2h", Duration.ofDays(1).plusHours(2)),
                Arguments.of("2h 5s", Duration.ofHours(2).plusSeconds(5)),
                Arguments.of("1d", Duration.ofDays(1)),
                Arguments.of("  2h   30m  ", Duration.ofHours(2).plusMinutes(30)),
                // ISO-8601, case-insensitive "P..."
                Arguments.of("PT2H30M", Duration.ofHours(2).plusMinutes(30)),
                Arguments.of("pt1h", Duration.ofHours(1)),
                // zero value via tokens or clock — "0s" is what formatHuman emits for zero, so it must parse back
                Arguments.of("00:00", Duration.ZERO),
                Arguments.of("0s", Duration.ZERO),
                Arguments.of("0m", Duration.ZERO),
                Arguments.of("0h", Duration.ZERO),
                Arguments.of("0d", Duration.ZERO),
                Arguments.of("PT0S", Duration.ZERO)
        );
    }

    @ParameterizedTest
    @MethodSource
    void parsesInvalid(String input, String expectedMessagePart) {
        assertThatThrownBy(() -> FriendlyDurationFormat.parseHuman(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedMessagePart);
    }

    // -------------------- Parsing: invalid inputs --------------------
    static Stream<Arguments> parsesInvalid() {
        return Stream.of(
                Arguments.of("abc", "Invalid duration"),
                Arguments.of("1:99", "minutes and seconds"),
                Arguments.of("00:00:99", "minutes and seconds"),
                Arguments.of("1x", "Invalid duration"),
                Arguments.of("--", "Invalid duration")
        );
    }

    @Test
    void parseNullThrowsNpe() {
        assertThatThrownBy(() -> FriendlyDurationFormat.parseHuman(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void parseEmptyThrowsIae() {
        assertThatThrownBy(() -> FriendlyDurationFormat.parseHuman(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty input");
        assertThatThrownBy(() -> FriendlyDurationFormat.parseHuman("  \t  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty input");
    }

    @ParameterizedTest
    @MethodSource
    void formats(Duration input, String expected) {
        assertThat(FriendlyDurationFormat.formatHuman(input)).isEqualTo(expected);
    }

    // -------------------- Formatting --------------------
    static Stream<Arguments> formats() {
        return Stream.of(
                Arguments.of(Duration.ofHours(2).plusMinutes(30), "2h 30m"),
                // 90m -> 1h 30m after normalization
                Arguments.of(Duration.ofMinutes(90), "1h 30m"),
                // 3600s -> 1h
                Arguments.of(Duration.ofSeconds(3600), "1h"),
                // 1d 2h
                Arguments.of(Duration.ofDays(1).plusHours(2), "1d 2h"),
                // days with minutes only (skip zero hours in the middle)
                Arguments.of(Duration.ofDays(1).plusMinutes(3), "1d 3m"),
                // basic single-unit cases (leading zeros trimmed)
                Arguments.of(Duration.ofSeconds(7), "7s"),
                Arguments.of(Duration.ofMinutes(5), "5m"),
                Arguments.of(Duration.ofHours(2), "2h"),
                Arguments.of(Duration.ofDays(2), "2d"),
                // skip middle zeros and trim trailing zeros
                Arguments.of(Duration.ofHours(1).plusSeconds(4), "1h 4s"), // minutes=0 skipped
                Arguments.of(Duration.ofMinutes(3).plusSeconds(4), "3m 4s"),
                Arguments.of(Duration.ofDays(2).plusMinutes(5), "2d 5m"), // hours=0 skipped
                Arguments.of(Duration.ofDays(1).plusSeconds(5), "1d 5s"), // hours=0, minutes=0 skipped
                Arguments.of(Duration.ofDays(1).plusHours(2).plusSeconds(5), "1d 2h 5s"), // minutes=0 skipped
                // all zeros -> 0s
                Arguments.of(Duration.ZERO, "0s")
        );
    }

    @ParameterizedTest
    @MethodSource("formats")
    void formattedValueParsesBackToSameDuration(Duration input, String formatted) {
        assertThat(FriendlyDurationFormat.parseHuman(formatted)).isEqualTo(input);
    }

    @Test
    void formatNullThrowsNpe() {
        assertThatThrownBy(() -> FriendlyDurationFormat.formatHuman(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void formatNegativeThrowsIae() {
        assertThatThrownBy(() -> FriendlyDurationFormat.formatHuman(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }
}
