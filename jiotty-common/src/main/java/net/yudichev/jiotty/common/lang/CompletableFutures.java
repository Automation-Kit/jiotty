package net.yudichev.jiotty.common.lang;

import com.google.common.collect.ImmutableList;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collector;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Collections.emptyList;
import static net.yudichev.jiotty.common.lang.DelayedExecutors.delayedExecutor;

@SuppressWarnings("WeakerAccess") // it's a library
public final class CompletableFutures {
    private CompletableFutures() {
    }

    public static <T> CompletableFuture<T> completable(Future<? extends T> future) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return future.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @SuppressWarnings("ZeroLengthArrayAllocation") // this is what we need
    public static CompletableFuture<Void> allOf(ImmutableList<CompletableFuture<?>> futures) {
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    public static <T> CompletableFuture<T> completedFuture() {
        return CompletableFuture.completedFuture(null);
    }

    public static CompletableFuture<Void> delay(long millis) {
        return CompletableFuture.runAsync(() -> {}, delayedExecutor(millis));
    }

    public static <T> CompletableFuture<T> failure(String message) {
        return failure(new RuntimeException(message));
    }

    public static <T> CompletableFuture<T> failure(Throwable exception) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(exception);
        return future;
    }

    public static <T> CompletableFuture<T> cancelled() {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.cancel(true);
        return future;
    }

    public static <T> Collector<CompletableFuture<T>, ?, CompletableFuture<List<T>>> toFutureOfList() {
        return toFutureOfList(CompletableFuture::join);
    }

    public static <T> Collector<CompletableFuture<T>, ?, CompletableFuture<List<Either<T, Throwable>>>> toFutureOfListWithErrors() {
        return toFutureOfList(future -> future.isCompletedExceptionally() ? Either.right(future.exceptionNow()) : Either.left(future.join()));
    }

    public static <T, R> Collector<CompletableFuture<T>, ?, CompletableFuture<List<R>>> toFutureOfList(Function<CompletableFuture<T>, R> futureToResult) {
        return Collector.of(
                ImmutableList::<CompletableFuture<T>>builder,
                ImmutableList.Builder::add,
                (builder1, builder2) ->
                        ImmutableList.<CompletableFuture<T>>builder()
                                     .addAll(builder1.build())
                                     .addAll(builder2.build()),
                builder -> {
                    ImmutableList<CompletableFuture<T>> listOfFutures = builder.build();
                    var result = new CompletableFuture<List<R>>();
                    //noinspection ZeroLengthArrayAllocation
                    CompletableFuture.allOf(listOfFutures.toArray(new CompletableFuture[0]))
                                     .handle((_, _) -> listOfFutures.stream()
                                                                    .map(future -> {
                                                                        assert future.isDone();
                                                                        return futureToResult.apply(future);
                                                                    })
                                                                    .toList())
                                     .whenComplete((list, failure) -> {
                                         if (failure != null) {
                                             result.completeExceptionally(failure);
                                         } else {
                                             result.complete(list);
                                         }
                                     });
                    // Propagate cancellation of the aggregate down to its inputs, so cancelling the returned future stops all outstanding work rather than
                    // leaving it running detached.
                    result.whenComplete((_, _) -> {
                        if (result.isCancelled()) {
                            listOfFutures.forEach(future -> future.cancel(true));
                        }
                    });
                    return result;
                }
        );
    }

    public static <T, R> Collector<T, ?, CompletableFuture<List<R>>> toFutureOfListChaining(Function<T, CompletableFuture<R>> operation) {
        Object builderMutex = new Object();
        return Collector.of(
                () -> new FutureChainBuilder<>(operation, builderMutex),
                FutureChainBuilder::accept,
                FutureChainBuilder::combinedWith,
                FutureChainBuilder::build
        );
    }

    public static <T> BiConsumer<T, Throwable> logErrorOnFailure(Logger logger, String errorMessageTemplate, Object... params) {
        return (_, e) -> {
            if (e != null) {
                logger.error(String.format(errorMessageTemplate, params), e);
            }
        };
    }

    public static <T> BiConsumer<T, Throwable> thenComplete(CompletableFuture<? super T> anotherFuture) {
        return (result, error) -> {
            if (error == null) {
                anotherFuture.complete(result);
            } else {
                anotherFuture.completeExceptionally(error);
            }
        };
    }

    private static class FutureChainBuilder<T, R> {
        private final Function<T, CompletableFuture<R>> operation;
        private final Object mutex;
        private CompletableFuture<List<R>> future;

        FutureChainBuilder(Function<T, CompletableFuture<R>> operation, Object mutex) {
            this.operation = checkNotNull(operation);
            this.mutex = checkNotNull(mutex);
        }

        public FutureChainBuilder<T, R> combinedWith(FutureChainBuilder<T, R> another) {
            if (future == null) {
                return another;
            } else if (another.future == null) {
                return this;
            } else {
                FutureChainBuilder<T, R> combinedBuilder = new FutureChainBuilder<>(operation, mutex);
                combinedBuilder.future = future.thenCompose(list1 -> another.future.thenApply(list2 -> {
                    synchronized (mutex) {
                        list1.addAll(list2);
                        return list1;
                    }
                }));
                return combinedBuilder;
            }
        }

        public void accept(T input) {
            if (future == null) {
                future = operation.apply(input)
                                  .thenApply(result -> {
                                      List<R> list = new ArrayList<>();
                                      list.add(result);
                                      return list;
                                  });
            } else {
                future = future.thenCompose(list ->
                                                    operation.apply(input)
                                                             .thenApply(result -> {
                                                                 synchronized (mutex) {
                                                                     list.add(result);
                                                                     return list;
                                                                 }
                                                             }));
            }
        }

        public CompletableFuture<List<R>> build() {
            return future == null ? CompletableFuture.completedFuture(emptyList()) : future;
        }
    }
}
