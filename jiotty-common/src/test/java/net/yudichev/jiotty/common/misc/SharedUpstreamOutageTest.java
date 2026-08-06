package net.yudichev.jiotty.common.misc;

import net.yudichev.jiotty.common.rest.HttpResponseException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import static net.yudichev.jiotty.common.misc.SharedUpstreamOutage.indicatesSharedOutage;
import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_GATEWAY_502;
import static net.yudichev.jiotty.common.rest.HttpStatuses.SERVICE_UNAVAILABLE_503;
import static net.yudichev.jiotty.common.rest.HttpStatuses.TOO_MANY_REQUESTS_429;
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class SharedUpstreamOutageTest {

    static Stream<Arguments> failures() {
        return Stream.of(
                // upstream is struggling for everyone
                arguments(new HttpResponseException(BAD_GATEWAY_502, "Bad Gateway"), true),
                // a throttle on the shared key/egress limits every caller at once
                arguments(new HttpResponseException(TOO_MANY_REQUESTS_429, "Too Many Requests"), true),
                arguments(new SocketTimeoutException("timed out"), true),
                // a connection pool with nothing left to hand out affects every caller of that pool
                arguments(new SQLTransientConnectionException("pool timed out"), true),
                // the verdict is read off the whole causal chain, not just the outermost throwable
                arguments(new CompletionException(new HttpResponseException(SERVICE_UNAVAILABLE_503, "Service Unavailable")), true),
                arguments(new RuntimeException(new IOException("connection reset")), true),
                // this caller's own request or data is at fault
                arguments(new HttpResponseException(UNAUTHORIZED_401, "Unauthorized"), false),
                arguments(new SQLIntegrityConstraintViolationException("duplicate key"), false),
                arguments(new SQLSyntaxErrorException("no such column"), false),
                arguments(new RuntimeException("account response unparseable"), false));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void classifiesFailureBySharedness(Throwable failure, boolean expectedSharedOutage) {
        assertThat(indicatesSharedOutage(failure)).isEqualTo(expectedSharedOutage);
    }
}
