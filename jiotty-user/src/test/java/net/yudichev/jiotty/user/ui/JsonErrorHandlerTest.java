package net.yudichev.jiotty.user.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static net.yudichev.jiotty.user.ui.JsonErrorHandler.sentenceCaseReason;
import static org.assertj.core.api.Assertions.assertThat;

class JsonErrorHandlerTest {
    @ParameterizedTest
    @CsvSource({
            "404, Not found",
            "405, Method not allowed",
            "416, Range not satisfiable",
            "500, Server error",
            "502, Bad gateway",
            "503, Service unavailable",
    })
    void sentenceCaseReason_translatesStandardStatusCodes(int status, String expectedReason) {
        assertThat(sentenceCaseReason(status)).isEqualTo(expectedReason);
    }

    @Test
    void sentenceCaseReason_unknownStatus_returnsStatusCodeText() {
        // Jetty's HttpStatus.getMessage falls back to the status code's decimal string for unrecognised codes.
        assertThat(sentenceCaseReason(799)).isEqualTo("799");
    }
}
