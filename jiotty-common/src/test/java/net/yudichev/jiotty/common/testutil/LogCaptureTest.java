package net.yudichev.jiotty.common.testutil;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogCaptureTest {
    @Test
    void captureByName_capturesEveryLoggerBelowTheName_atTheLevelOrAbove() {
        try (var capture = LogCapture.capture("net.yudichev.jiotty.common.testutil.capturetest", Level.DEBUG)) {
            LogManager.getLogger("net.yudichev.jiotty.common.testutil.capturetest.a.First").debug("first");
            LogManager.getLogger("net.yudichev.jiotty.common.testutil.capturetest.Second").info("second");
            LogManager.getLogger("net.yudichev.jiotty.common.testutil.capturetest.Third").trace("too fine");
            LogManager.getLogger("net.yudichev.jiotty.common.testutil.other.Fourth").info("elsewhere");

            assertThat(capture.lines()).extracting(LogCapture.CapturedLine::message).containsExactly("first", "second");
        }
    }

    @Test
    void captureByClass_capturesTheLoggerOfThatClass() {
        try (var capture = LogCapture.capture(LogCaptureTest.class, Level.INFO)) {
            LogManager.getLogger(LogCaptureTest.class).info("mine");

            assertThat(capture.lines()).singleElement().returns("mine", LogCapture.CapturedLine::message);
        }
    }
}
