package net.yudichev.jiotty.common.security;

import net.yudichev.jiotty.common.geo.LatLon;
import net.yudichev.jiotty.common.geo.LatLonRectangle;
import org.junit.jupiter.api.Test;

import static net.yudichev.jiotty.common.security.LogRedaction.redact;
import static net.yudichev.jiotty.common.security.LogRedaction.redacted;
import static org.assertj.core.api.Assertions.assertThat;

class LogRedactionTest {
    @Test
    void redactStringKeepsFirstThreeCharsAndEllipsis() {
        assertThat(redact("abcdefghij")).isEqualTo("abc…");
    }

    @Test
    void redactShortStringIsFullyMasked() {
        assertThat(redact("ab")).isEqualTo("…");
        assertThat(redact("abc")).isEqualTo("…");
    }

    @Test
    void redactedStringDelegatesToRedact() {
        var buffer = new StringBuilder();
        redacted("abcdefghij").formatTo(buffer);
        assertThat(buffer.toString()).isEqualTo("abc…");
    }

    @Test
    void redactedStringRangeKeepsRegionPrefixWithoutSubstring() {
        var buffer = new StringBuilder("VIN:");
        redacted("VIN:5YJ3E1EA1JF000001", 4, 21).formatTo(buffer);
        assertThat(buffer.toString()).isEqualTo("VIN:5YJ…").doesNotContain("000001");
    }

    @Test
    void redactedStringRangeOfThreeOrFewerCharsIsFullyMasked() {
        var buffer = new StringBuilder();
        redacted("abcXY", 3, 5).formatTo(buffer);
        assertThat(buffer.toString()).isEqualTo("…");
    }

    @Test
    void redactLatLonCoarsensToOneDecimalAndOmitsPreciseValue() {
        assertThat(redact(new LatLon(51.501234, -0.142567)))
                .isEqualTo("~{51.5,-0.1}")
                .doesNotContain("51.501234", "-0.142567");
    }

    @Test
    void redactNullLatLonRendersAsNull() {
        assertThat(redact((LatLon) null)).isEqualTo("null");
    }

    @Test
    void redactedLatLonDelegatesToRedact() {
        var buffer = new StringBuilder();
        redacted(new LatLon(51.501234, -0.142567)).formatTo(buffer);
        assertThat(buffer.toString()).isEqualTo("~{51.5,-0.1}");
    }

    @Test
    void redactLatLonRectangleCoarsensAllBoundsAndOmitsPreciseValues() {
        assertThat(redact(new LatLonRectangle(51.501234, 51.561234, -0.142567, -0.012567)))
                .isEqualTo("~[{51.5,-0.1}..{51.6,0.0}]")
                .doesNotContain("51.501234", "51.561234", "-0.142567", "-0.012567");
    }

    @Test
    void redactNullLatLonRectangleRendersAsNull() {
        assertThat(redact((LatLonRectangle) null)).isEqualTo("null");
    }
}
