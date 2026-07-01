package net.yudichev.jiotty.common.security;

import net.yudichev.jiotty.common.geo.LatLon;
import net.yudichev.jiotty.common.geo.LatLonRectangle;
import org.junit.jupiter.api.Test;

import static net.yudichev.jiotty.common.security.LogRedaction.appendRedacted;
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
    void appendRedactedStringAppendsSameFormAsRedact() {
        var buffer = new StringBuilder();
        appendRedacted(buffer, "abcdefghij");
        assertThat(buffer.toString()).isEqualTo("abc…");
    }

    @Test
    void appendRedactedStringRangeKeepsRegionPrefixWithoutSubstring() {
        var buffer = new StringBuilder("VIN:");
        appendRedacted(buffer, "VIN:5YJ3E1EA1JF000001", 4, 21);
        assertThat(buffer.toString()).isEqualTo("VIN:5YJ…").doesNotContain("000001");
    }

    @Test
    void appendRedactedStringRangeOfThreeOrFewerCharsIsFullyMasked() {
        var buffer = new StringBuilder();
        appendRedacted(buffer, "abcXY", 3, 5);
        assertThat(buffer.toString()).isEqualTo("…");
    }

    @Test
    void redactedStringDelegatesToAppendRedacted() {
        var buffer = new StringBuilder();
        redacted("abcdefghij").formatTo(buffer);
        assertThat(buffer.toString()).isEqualTo("abc…");
    }

    @Test
    void redactedStringRangeDelegatesToAppendRedacted() {
        var buffer = new StringBuilder("VIN:");
        redacted("VIN:5YJ3E1EA1JF000001", 4, 21).formatTo(buffer);
        assertThat(buffer.toString()).isEqualTo("VIN:5YJ…").doesNotContain("000001");
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
    void appendRedactedLatLonAppendsSameFormAsRedact() {
        var buffer = new StringBuilder();
        appendRedacted(buffer, new LatLon(51.501234, -0.142567));
        assertThat(buffer.toString()).isEqualTo("~{51.5,-0.1}");
    }

    @Test
    void redactedLatLonDelegatesToAppendRedacted() {
        var buffer = new StringBuilder();
        redacted(new LatLon(51.501234, -0.142567)).formatTo(buffer);
        assertThat(buffer.toString()).isEqualTo("~{51.5,-0.1}");
    }

    @Test
    void redactedLatLonRectangleDelegatesToAppendRedacted() {
        var buffer = new StringBuilder();
        redacted(new LatLonRectangle(51.501234, 51.561234, -0.142567, -0.012567)).formatTo(buffer);
        assertThat(buffer.toString()).isEqualTo("~[{51.5,-0.1}..{51.6,0.0}]");
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
