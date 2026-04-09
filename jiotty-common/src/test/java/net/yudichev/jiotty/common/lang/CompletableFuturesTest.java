package net.yudichev.jiotty.common.lang;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.quality.Strictness.LENIENT;

@ExtendWith(MockitoExtension.class)
class CompletableFuturesTest {

    @Test
    void toFutureOfList() {
        CompletableFuture<String> future1 = new CompletableFuture<>();
        CompletableFuture<String> future2 = new CompletableFuture<>();

        CompletableFuture<List<String>> result = IntStream.rangeClosed(1, 2)
                                                          .mapToObj(value -> value == 1 ? future1 : future2)
                                                          .collect(CompletableFutures.toFutureOfList());

        assertThat(result).isNotDone();

        future1.complete("result1");
        assertThat(result).isNotDone();

        future2.complete("result2");
        assertThat(result).isDone();
        assertThat(result.getNow(null)).containsExactly("result1", "result2");
    }

    @Test
    void toFutureOfListPropagatesFailure() {
        RuntimeException failure = new RuntimeException("boom");
        CompletableFuture<String> future1 = new CompletableFuture<>();
        CompletableFuture<String> future2 = new CompletableFuture<>();

        CompletableFuture<List<String>> result = Stream.of(future1, future2)
                                                       .collect(CompletableFutures.toFutureOfList());

        future1.complete("result1");
        assertThat(result).isNotDone();

        future2.completeExceptionally(failure);
        assertThat(result).isCompletedExceptionally();
        assertThatThrownBy(result::join)
                .isInstanceOf(CompletionException.class)
                .hasCause(failure);
    }

    @Test
    void toFutureOfListWithErrors() {
        RuntimeException failure = new RuntimeException("boom");
        CompletableFuture<String> future1 = new CompletableFuture<>();
        CompletableFuture<String> future2 = new CompletableFuture<>();
        CompletableFuture<String> future3 = new CompletableFuture<>();

        CompletableFuture<List<Either<String, Throwable>>> result = Stream.of(future1, future2, future3)
                                                                          .collect(CompletableFutures.toFutureOfListWithErrors());

        assertThat(result).isNotDone();

        future1.complete("result1");
        assertThat(result).isNotDone();

        future2.completeExceptionally(failure);
        assertThat(result).isNotDone();

        future3.complete("result3");
        assertThat(result).isDone();
        assertThat(result.getNow(null)).containsExactly(
                Either.left("result1"),
                Either.right(failure),
                Either.left("result3"));
    }

    @Test
    void toFutureOfListWithErrorsEmpty() {
        CompletableFuture<List<Either<String, Throwable>>> result = Stream.<CompletableFuture<String>>of()
                                                                          .collect(CompletableFutures.toFutureOfListWithErrors());

        assertThat(result).isDone();
        assertThat(result.getNow(null)).isEmpty();
    }

    @Test
    @MockitoSettings(strictness = LENIENT)
    void toFutureOfListChaining(@Mock Function<? super Integer, CompletableFuture<String>> operation) {
        CompletableFuture<String> future1 = new CompletableFuture<>();
        when(operation.apply(1)).thenReturn(future1);
        CompletableFuture<String> future2 = new CompletableFuture<>();
        when(operation.apply(2)).thenReturn(future2);

        CompletableFuture<List<String>> result = IntStream.rangeClosed(1, 2)
                                                          .boxed()
                                                          .collect(CompletableFutures.toFutureOfListChaining(operation));

        verify(operation).apply(1);
        verify(operation, never()).apply(2);
        assertThat(result).isNotDone();

        future1.complete("result1");
        verify(operation).apply(2);
        assertThat(result).isNotDone();

        future2.complete("result2");
        assertThat(result).isDone();
        assertThat(result.getNow(null)).containsExactly("result1", "result2");
    }

    @Test
    void toFutureOfListChainingEmptyList(@Mock Function<? super Integer, CompletableFuture<String>> operation) {
        CompletableFuture<List<String>> result = ImmutableList.<Integer>of().stream()
                                                              .collect(CompletableFutures.toFutureOfListChaining(operation));

        assertThat(result).isDone();
        assertThat(result.getNow(null)).isEmpty();
    }
}