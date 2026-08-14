package net.yudichev.jiotty.common.testutil;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PiiLogGuardTest {
    private static final Logger logger = LogManager.getLogger(PiiLogGuardTest.class);

    @BeforeAll
    static void installGuard() {
        // The Extension registration is a consumer's to supply (see PiiLogGuardInstaller), so the end-to-end cases below install the guard themselves.
        new PiiLogGuardInstaller(List.of("net.yudichev")).install();
    }

    @ParameterizedTest
    @ValueSource(strings = {"signed in as alex.smith@example.co.uk",
            "recipients=[a@b.io]",
            "car VIN 5YJ3E1EA1JF000001 reported",
            "LatLon[lat=51.501234, lon=-0.142567]",
            "{\"lat\": 51.501234}",
            "longitude=-0.142567"})
    void scanNamesTheKindOfPersonalDataFound(String text) {
        assertThat(PiiLogGuard.scan(text)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"UserProfile[id=u016, email=ale…, displayName=Ale…, timezone=Europe/London]",
            "arrived at ~{51.5,-0.1}",
            "CarChargeRecordable[carId=VIN:5YJ…, soc=72.5]",
            "TRANSACTIONFAILED after 3 attempts",
            "ratedCostCcyPerkW=0.00833333333333333",
            "available SoC boost 4.1544036666607553E-4% is too low",
            "latency 12.345678 ms"})
    void scanPassesRedactedAndInnocentText(String text) {
        assertThat(PiiLogGuard.scan(text)).isEmpty();
    }

    /// Exercises the guard as a build runs it: through an ordinary logger, at a level the installer had to raise for the line to render at all.
    @Test
    void recordsPersonalDataAnOrdinaryLogLineCarries() {
        logger.debug("home is at lat={}", 51.501234);

        assertThat(PiiLogGuard.drainViolations()).anySatisfy(violation -> assertThat(violation)
                .contains("precise coordinate")
                .contains(PiiLogGuardTest.class.getName())
                .contains("home is at lat={}")
                .doesNotContain("51.501234"));
    }

    @Test
    void recordsPersonalDataAnExceptionMessageCarries() {
        logger.debug("lookup failed", new IllegalStateException("no route for alex.smith@example.co.uk"));

        assertThat(PiiLogGuard.drainViolations()).anySatisfy(violation -> assertThat(violation)
                .contains("email address")
                .doesNotContain("alex.smith"));
    }
}
