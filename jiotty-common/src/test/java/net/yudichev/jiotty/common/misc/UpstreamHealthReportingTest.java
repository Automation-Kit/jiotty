package net.yudichev.jiotty.common.misc;

import net.yudichev.jiotty.common.async.backoff.RecordingRetryableOperationExecutor;
import net.yudichev.jiotty.common.rest.HttpResponseException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static net.yudichev.jiotty.common.misc.UpstreamHealthReporting.reportingHealth;
import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_GATEWAY_502;
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpstreamHealthReportingTest {

    private final RecordingRetryableOperationExecutor retryExecutor = new RecordingRetryableOperationExecutor();
    private final RecordingUpstreamHealthHandler healthHandler = new RecordingUpstreamHealthHandler();

    @Test
    void success_reportsUpstreamHealthyAndDeliversTheResult() {
        CompletableFuture<String> future = reportingHealth(retryExecutor, healthHandler, "get the thing", "thing API call failed",
                                                           () -> completedFuture("result"));

        assertThat(future.join()).isEqualTo("result");
        assertThat(healthHandler.successCount()).isEqualTo(1);
        assertThat(healthHandler.failures()).isEmpty();
    }

    @Test
    void sharedOutageFailure_reportsUpstreamFailureUnderTheGivenMessage() {
        CompletableFuture<String> future = reportingHealth(retryExecutor, healthHandler, "get the thing", "thing API call failed",
                                                           () -> failedFuture(new HttpResponseException(BAD_GATEWAY_502, "Bad Gateway")));

        assertThatThrownBy(future::join).hasCauseInstanceOf(HttpResponseException.class);
        assertThat(healthHandler.failures()).singleElement().satisfies(f -> assertThat(f).startsWith("thing API call failed"));
        assertThat(healthHandler.successCount()).isZero();
    }

    @Test
    void subjectSpecificFailure_reportsNothing() {
        // The upstream answered — a 4xx is a verdict on this request, not an outage every caller shares.
        CompletableFuture<String> future = reportingHealth(retryExecutor, healthHandler, "get the thing", "thing API call failed",
                                                           () -> failedFuture(new HttpResponseException(UNAUTHORIZED_401, "Unauthorized")));

        assertThatThrownBy(future::join).hasCauseInstanceOf(HttpResponseException.class);
        assertThat(healthHandler.failures()).isEmpty();
        assertThat(healthHandler.successCount()).isZero();
    }

    @Test
    void routesTheOperationThroughTheRetryExecutor() {
        reportingHealth(retryExecutor, healthHandler, "get the thing", "thing API call failed", () -> completedFuture("result"));

        assertThat(retryExecutor.operationNames()).containsExactly("get the thing");
    }

    @Test
    void throwingHealthHandler_doesNotFailASuccessfulCall() {
        // A health-handler fault must be contained, never delivered to the caller as the call's outcome.
        CompletableFuture<String> future = reportingHealth(retryExecutor, new ThrowingUpstreamHealthHandler(), "get the thing", "thing API call failed",
                                                           () -> completedFuture("result"));

        assertThat(future.join()).isEqualTo("result");
    }
}
