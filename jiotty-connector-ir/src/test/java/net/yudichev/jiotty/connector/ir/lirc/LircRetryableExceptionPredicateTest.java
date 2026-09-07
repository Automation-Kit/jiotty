package net.yudichev.jiotty.connector.ir.lirc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketException;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;

class LircRetryableExceptionPredicateTest {

    private static final LircRetryableExceptionPredicate PREDICATE = new LircRetryableExceptionPredicate();

    @Test
    void droppedSocketIsRetryable() {
        assertThat(PREDICATE.test(new SocketException("connection reset"))).isTrue();
    }

    @Test
    void refusedCommandIsRetryable() {
        assertThat(PREDICATE.test(new LircServerException("failed to send tx start command"))).isTrue();
    }

    /// The handler applies this to the failure as thrown, and a dropped socket reaches it wrapped, so the verdict has to come off the whole chain.
    @Test
    void wrappedDroppedSocketIsRetryable() {
        assertThat(PREDICATE.test(new CompletionException(new RuntimeException(new SocketException("connection reset"))))).isTrue();
    }

    @Test
    void anythingElseIsNotRetryable() {
        assertThat(PREDICATE.test(new IOException("no route to host"))).isFalse();
        assertThat(PREDICATE.test(new IllegalStateException("bad state"))).isFalse();
    }
}
