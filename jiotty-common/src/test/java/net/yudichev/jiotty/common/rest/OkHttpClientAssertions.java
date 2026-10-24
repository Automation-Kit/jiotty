package net.yudichev.jiotty.common.rest;

import okhttp3.OkHttpClient;

import java.time.Duration;
import java.util.List;

import static java.lang.Math.toIntExact;
import static org.assertj.core.api.Assertions.assertThat;

/// Assertions on how a real [OkHttpClient] is configured.
public final class OkHttpClientAssertions {
    private OkHttpClientAssertions() {
    }

    /// Asserts that `client` gives up on a connect, a read, a write and a whole call once `timeout` has passed.
    public static void assertGivesUpAfter(OkHttpClient client, Duration timeout) {
        assertThat(List.of(client.connectTimeoutMillis(), client.readTimeoutMillis(), client.writeTimeoutMillis(), client.callTimeoutMillis()))
                .containsOnly(toIntExact(timeout.toMillis()));
    }
}
