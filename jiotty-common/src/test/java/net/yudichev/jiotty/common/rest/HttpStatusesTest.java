package net.yudichev.jiotty.common.rest;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static net.yudichev.jiotty.common.rest.HttpStatuses.isServerError;
import static net.yudichev.jiotty.common.rest.HttpStatuses.isSuccess;
import static org.assertj.core.api.Assertions.assertThat;

class HttpStatusesTest {

    @ParameterizedTest
    @CsvSource({
            // boundaries of the 2xx class
            "199, false",
            "200, true",
            "201, true",
            "204, true",
            "299, true",
            "300, false",
            // 4xx and 5xx
            "404, false",
            "503, false"})
    void classifiesSuccessByStatus(int code, boolean expectedSuccess) {
        assertThat(isSuccess(code)).isEqualTo(expectedSuccess);
    }

    @ParameterizedTest
    @CsvSource({
            // boundaries of the 5xx class
            "499, false",
            "500, true",
            "503, true",
            "599, true",
            "600, false",
            // 2xx and 4xx
            "200, false",
            "429, false"})
    void classifiesServerErrorByStatus(int code, boolean expectedServerError) {
        assertThat(isServerError(code)).isEqualTo(expectedServerError);
    }
}
