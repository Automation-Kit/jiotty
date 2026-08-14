package net.yudichev.jiotty.common.testutil;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class PiiLogGuardInstallerTest {
    private static final Logger logger = LogManager.getLogger(PiiLogGuardInstallerTest.class);

    private PiiLogGuardInstaller installer;

    @BeforeEach
    void setUp() {
        installer = new PiiLogGuardInstaller(List.of("net.yudichev"));
        installer.install();
        PiiLogGuard.drainViolations();
    }

    @Test
    void failsTheTestThatDrainsAViolation(@Mock ExtensionContext context) {
        logger.debug("registered {}", "alex.smith@example.co.uk");

        assertThatThrownBy(() -> installer.afterEach(context))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("email address")
                .hasMessageContaining(PiiLogGuardInstallerTest.class.getName())
                .hasMessageContaining("registered {}");
    }

    @Test
    void passesWhenTheLogsAreClean(@Mock ExtensionContext context) {
        logger.debug("signed in as {}", "u016");

        assertThatCode(() -> installer.afterEach(context)).doesNotThrowAnyException();
    }

    @Test
    void reportsEachViolationOnce(@Mock ExtensionContext context) {
        logger.debug("invited {}", "alex.smith@example.co.uk");
        assertThatThrownBy(() -> installer.afterEach(context)).isInstanceOf(AssertionError.class);

        assertThatCode(() -> installer.afterEach(context)).doesNotThrowAnyException();
    }
}
