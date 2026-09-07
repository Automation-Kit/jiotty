package net.yudichev.jiotty.common.async.backoff;

import net.yudichev.jiotty.common.lang.backoff.BackOff;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CompletionException;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BackingOffExceptionHandlerImplTest {
    private static final long BACKOFF_MS = 1234L;
    private static final long MAX_ELAPSED_TIME_MS = 30_000L;

    @Mock
    private BackOff backOff;
    @Mock
    private Predicate<? super Throwable> retryableExceptionPredicate;

    @Test
    void retryableException_yieldsTheBackoffDelay() {
        var failure = new RuntimeException("transient");
        when(retryableExceptionPredicate.test(failure)).thenReturn(true);
        when(backOff.nextBackOffMillis()).thenReturn(BACKOFF_MS);

        assertThat(handler().handle("op", failure)).hasValue(BACKOFF_MS);
    }

    @Test
    void nonRetryableException_yieldsEmptyWithoutConsumingTheBackoff() {
        var failure = new RuntimeException("permanent");
        when(retryableExceptionPredicate.test(failure)).thenReturn(false);

        assertThat(handler().handle("op", failure)).isEmpty();
        // a verdict of "never going to work" must not spend the operation's retry budget
        verifyNoInteractions(backOff);
    }

    /// The handler asks about the failure as thrown and nothing else, so a predicate that would have said "retryable" about a wrapped cause cannot override
    /// the verdict on the throwable it was handed. A predicate caring about a cause has to walk the chain itself.
    @Test
    void predicateSeesTheFailureAsThrown_notItsCauses() {
        var cause = new IllegalStateException("underlying");
        var failure = new CompletionException(cause);
        lenient().when(retryableExceptionPredicate.test(cause)).thenReturn(true);
        when(retryableExceptionPredicate.test(failure)).thenReturn(false);

        assertThat(handler().handle("op", failure)).isEmpty();
        verify(retryableExceptionPredicate, never()).test(cause);
        verifyNoInteractions(backOff);
    }

    @Test
    void exhaustedBackoff_givesUpAndCarriesTheFailureAsCause() {
        var failure = new RuntimeException("transient");
        when(retryableExceptionPredicate.test(failure)).thenReturn(true);
        when(backOff.nextBackOffMillis()).thenReturn(BackOff.STOP);
        when(backOff.getMaxElapsedTimeMillis()).thenReturn(MAX_ELAPSED_TIME_MS);

        assertThatThrownBy(() -> handler().handle("op", failure))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Operation op is being retried for too long (" + MAX_ELAPSED_TIME_MS + "ms)")
                .hasCause(failure);
    }

    private BackingOffExceptionHandlerImpl handler() {
        return new BackingOffExceptionHandlerImpl(backOff, retryableExceptionPredicate);
    }
}
