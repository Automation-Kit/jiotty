package net.yudichev.jiotty.connector.tesla.fleet;

import net.yudichev.jiotty.common.rest.HttpResponseException;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/// Assertions on how a Fleet call ended.
final class FleetCallAssertions {
    private FleetCallAssertions() {
    }

    /// Asserts that `result` has failed with an [HttpResponseException] carrying `status` somewhere in the failure's causal chain.
    public static void assertFailsWithStatus(CompletableFuture<?> result, int status) {
        assertThat(result).failsWithin(Duration.ZERO)
                          .withThrowableThat()
                          .satisfies(failure -> assertThat(HttpResponseException.findIn(failure))
                                  .hasValueSatisfying(httpFailure -> assertThat(httpFailure.statusCode()).isEqualTo(status)));
    }
}
