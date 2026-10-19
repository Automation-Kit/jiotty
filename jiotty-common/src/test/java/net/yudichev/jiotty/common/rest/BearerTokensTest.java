package net.yudichev.jiotty.common.rest;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BearerTokensTest {
    @ParameterizedTest
    @CsvSource(nullValues = "NONE", delimiter = '|', value = {
            "'Bearer abc'      | abc",
            "'Bearer   abc  '  | abc",
            "NONE              | NONE",
            "'Basic abc'       | NONE",
            "'bearer abc'      | NONE",
            "'Bearer '         | NONE",
            "'Bearer    '      | NONE",
    })
    void readsTheTokenOfABearerHeaderOnly(@Nullable String header, @Nullable String expectedToken) {
        assertThat(BearerTokens.fromAuthorizationHeader(Optional.ofNullable(header))).isEqualTo(Optional.ofNullable(expectedToken));
    }
}
