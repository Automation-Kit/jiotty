package net.yudichev.jiotty.common.misc;

import net.yudichev.jiotty.common.rest.HttpResponseException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import static net.yudichev.jiotty.common.misc.RejectedCredential.indicatesRejectedCredential;
import static net.yudichev.jiotty.common.rest.HttpStatuses.FORBIDDEN_403;
import static net.yudichev.jiotty.common.rest.HttpStatuses.INTERNAL_SERVER_ERROR_500;
import static net.yudichev.jiotty.common.rest.HttpStatuses.TOO_MANY_REQUESTS_429;
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class RejectedCredentialTest {

    static Stream<Arguments> classifiesWhetherTheCredentialWasRejected() {
        return Stream.of(
                arguments(new HttpResponseException(UNAUTHORIZED_401, "{\"error\":\"token expired\"}"), true),
                // the verdict is read off the whole causal chain, which is how it survives the wrapping a completed future adds
                arguments(new CompletionException(new HttpResponseException(UNAUTHORIZED_401, "Unauthorized")), true),
                arguments(new IllegalStateException("giving up", new CompletionException(new HttpResponseException(UNAUTHORIZED_401, "Unauthorized"))), true),
                // a scope the credential never had, or a resource this account may not touch: re-authenticating fixes neither
                arguments(new HttpResponseException(FORBIDDEN_403, "{\"error\":\"insufficient scope\"}"), false),
                arguments(new HttpResponseException(INTERNAL_SERVER_ERROR_500, "Internal Server Error"), false),
                arguments(new HttpResponseException(TOO_MANY_REQUESTS_429, "Too Many Requests"), false),
                arguments(new IOException("connection reset"), false),
                arguments(new RuntimeException("response unparseable"), false),
                arguments(new CompletionException(new HttpResponseException(FORBIDDEN_403, "Forbidden")), false));
    }

    @ParameterizedTest
    @MethodSource
    void classifiesWhetherTheCredentialWasRejected(Throwable failure, boolean expectedRejected) {
        assertThat(indicatesRejectedCredential(failure)).isEqualTo(expectedRejected);
    }
}
