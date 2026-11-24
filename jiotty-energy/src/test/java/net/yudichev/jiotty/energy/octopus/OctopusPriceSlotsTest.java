package net.yudichev.jiotty.energy.octopus;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class OctopusPriceSlotsTest {
    @ParameterizedTest
    @CsvSource({"2026-11-21T10:30:00Z, 2026-11-21T10:30:00Z",
            "2026-11-21T10:47:13Z, 2026-11-21T10:30:00Z",
            "1969-12-31T23:59:59Z, 1969-12-31T23:30:00Z"})
    void floorToSlot_isTheStartOfTheRunningSlot(Instant instant, Instant expectedSlot) {
        assertThat(OctopusPriceSlots.floorToSlot(instant)).isEqualTo(expectedSlot);
    }

    @ParameterizedTest
    @CsvSource({
            // before the time of day
            "2026-11-21T17:00:00Z, 2026-11-21T18:00:00Z",
            // exactly at the time of day
            "2026-11-21T18:00:00Z, 2026-11-22T18:00:00Z",
            // after the time of day
            "2026-11-21T19:00:00Z, 2026-11-22T18:00:00Z",
            // across the spring-forward day: 18:00 BST
            "2026-03-28T19:00:00Z, 2026-03-29T17:00:00Z",
            // across the fall-back day: 18:00 GMT
            "2026-10-24T18:00:00Z, 2026-10-25T18:00:00Z"})
    void nextUkTimeOfDayAfter_isTheNextUkClockTime(Instant now, Instant expected) {
        assertThat(OctopusPriceSlots.nextUkTimeOfDayAfter(now, LocalTime.of(18, 0))).isEqualTo(expected);
    }
}
