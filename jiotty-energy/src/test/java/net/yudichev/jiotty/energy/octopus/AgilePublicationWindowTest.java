package net.yudichev.jiotty.energy.octopus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class AgilePublicationWindowTest {

    @ParameterizedTest
    @MethodSource
    void isOpen(Instant now, boolean expectedOpen) {
        assertThat(AgilePublicationWindow.isOpen(now)).isEqualTo(expectedOpen);
    }

    static Stream<Arguments> isOpen() {
        return Stream.of(
                // GMT: the window runs 16:00Z to 23:00Z
                arguments(Instant.parse("2024-01-10T15:59:59Z"), false),
                arguments(Instant.parse("2024-01-10T16:00:00Z"), true),
                arguments(Instant.parse("2024-01-10T22:59:59Z"), true),
                arguments(Instant.parse("2024-01-10T23:00:00Z"), false),
                // BST: the same London wall clock is an hour earlier in UTC
                arguments(Instant.parse("2024-07-10T14:59:59Z"), false),
                arguments(Instant.parse("2024-07-10T15:00:00Z"), true),
                arguments(Instant.parse("2024-07-10T21:59:59Z"), true),
                arguments(Instant.parse("2024-07-10T22:00:00Z"), false));
    }

    @ParameterizedTest
    @MethodSource
    void expectedCoverage(Instant now, Instant expected) {
        assertThat(AgilePublicationWindow.expectedCoverage(now)).isEqualTo(expected);
    }

    static Stream<Arguments> expectedCoverage() {
        return Stream.of(
                // inside a window, tomorrow's prices are due, so the profile should reach tomorrow's boundary
                arguments(Instant.parse("2024-01-10T16:30:00Z"), Instant.parse("2024-01-11T23:00:00Z")),
                arguments(Instant.parse("2024-07-10T15:30:00Z"), Instant.parse("2024-07-11T22:00:00Z")),
                // the day BST begins: tomorrow's boundary lands an hour earlier in UTC than today's
                arguments(Instant.parse("2024-03-30T16:30:00Z"), Instant.parse("2024-03-31T22:00:00Z")),
                // the day BST ends
                arguments(Instant.parse("2024-10-26T15:30:00Z"), Instant.parse("2024-10-27T23:00:00Z")),
                // before a window opens, only the current day is due
                arguments(Instant.parse("2024-01-10T09:00:00Z"), Instant.parse("2024-01-10T23:00:00Z")),
                // past the boundary the day just rolled over is the one that should be held
                arguments(Instant.parse("2024-01-10T23:30:00Z"), Instant.parse("2024-01-11T23:00:00Z")));
    }

    /// The day being published starts one Agile day before the coverage it brings, which is what tells a profile that merely stops short from one with a hole
    /// that publication starts after.
    @Test
    void expectedPublicationStartIsOneAgileDayBeforeTheCoverageItBrings() {
        Instant duringWindow = Instant.parse("2024-01-10T16:30:00Z");
        assertThat(AgilePublicationWindow.expectedPublicationStart(duringWindow)).isEqualTo(Instant.parse("2024-01-10T23:00:00Z"));
        // past the boundary the same publication is still the one due, so its start does not move
        assertThat(AgilePublicationWindow.expectedPublicationStart(Instant.parse("2024-01-10T23:30:00Z")))
                .isEqualTo(Instant.parse("2024-01-10T23:00:00Z"));
    }

    /// Crossing the boundary must not move the goalposts: the day that rolls over at 23:00 is the one the window just published, so a poll still chasing it
    /// keeps chasing the same instant rather than jumping to a day Octopus has not published yet.
    @Test
    void expectedCoverageIsContinuousAcrossTheDayBoundary() {
        Instant boundary = AgilePublicationWindow.closesAt(Instant.parse("2024-01-10T18:00:00Z"));
        assertThat(AgilePublicationWindow.expectedCoverage(boundary.minusMillis(1)))
                .isEqualTo(AgilePublicationWindow.expectedCoverage(boundary));
    }

    @ParameterizedTest
    @MethodSource
    void closesAt(Instant now, Instant expectedClose) {
        assertThat(AgilePublicationWindow.closesAt(now)).isEqualTo(expectedClose);
    }

    static Stream<Arguments> closesAt() {
        return Stream.of(
                arguments(Instant.parse("2024-01-10T16:30:00Z"), Instant.parse("2024-01-10T23:00:00Z")),
                arguments(Instant.parse("2024-07-10T15:30:00Z"), Instant.parse("2024-07-10T22:00:00Z")),
                arguments(Instant.parse("2024-03-30T16:30:00Z"), Instant.parse("2024-03-30T23:00:00Z")),
                arguments(Instant.parse("2024-10-26T15:30:00Z"), Instant.parse("2024-10-26T22:00:00Z")),
                // past the boundary the Agile day has rolled over, so the next one closes a day later
                arguments(Instant.parse("2024-01-10T23:00:00Z"), Instant.parse("2024-01-11T23:00:00Z")),
                arguments(Instant.parse("2024-01-10T23:30:00Z"), Instant.parse("2024-01-11T23:00:00Z")));
    }

    @ParameterizedTest
    @MethodSource
    void nextWindowStartAfter(Instant now, Instant expectedStart) {
        assertThat(AgilePublicationWindow.nextWindowStartAfter(now)).isEqualTo(expectedStart);
    }

    static Stream<Arguments> nextWindowStartAfter() {
        return Stream.of(
                // before today's window opens, it is today's
                arguments(Instant.parse("2024-01-10T09:00:00Z"), Instant.parse("2024-01-10T16:00:00Z")),
                arguments(Instant.parse("2024-07-10T09:00:00Z"), Instant.parse("2024-07-10T15:00:00Z")),
                // from inside the window, and from after it, the next one is tomorrow's
                arguments(Instant.parse("2024-01-10T16:00:00Z"), Instant.parse("2024-01-11T16:00:00Z")),
                arguments(Instant.parse("2024-01-10T23:30:00Z"), Instant.parse("2024-01-11T16:00:00Z")),
                arguments(Instant.parse("2024-07-10T22:30:00Z"), Instant.parse("2024-07-11T15:00:00Z")),
                // the night BST begins: tomorrow's 16:00 London lands an hour earlier in UTC
                arguments(Instant.parse("2024-03-30T23:30:00Z"), Instant.parse("2024-03-31T15:00:00Z")));
    }

    /// The window is open right up to the instant it closes, and closes exactly one Agile day before the coverage it is waiting for.
    @Test
    void closesAtIsTheEdgeOfTheOpenWindow() {
        var duringWindow = Instant.parse("2024-07-10T18:00:00Z");
        Instant closesAt = AgilePublicationWindow.closesAt(duringWindow);
        assertThat(AgilePublicationWindow.isOpen(closesAt.minusMillis(1))).isTrue();
        assertThat(AgilePublicationWindow.isOpen(closesAt)).isFalse();
        assertThat(AgilePublicationWindow.expectedCoverage(duringWindow)).isEqualTo(closesAt.plusSeconds(86400));
    }
}
