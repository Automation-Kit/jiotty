package net.yudichev.jiotty.common.async;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static net.yudichev.jiotty.common.lang.Closeable.closeIfNotNull;
import static org.assertj.core.api.Assertions.assertThat;

class SingleThreadedSchedulingExecutorTest {
    private ListenerBackedTaskExceptionHandlerRegistry exceptionHandler;
    private SingleThreadedSchedulingExecutor executor;

    @BeforeEach
    void setUp() {
        exceptionHandler = new ListenerBackedTaskExceptionHandlerRegistry();
        executor = new SingleThreadedSchedulingExecutor("test", exceptionHandler);
    }

    @AfterEach
    void tearDown() {
        closeIfNotNull(executor);
    }

    @Test
    void notifiesExceptionHandlerWhenExecutedTaskFails() {
        var captured = new CompletableFuture<Throwable>();
        exceptionHandler.addExceptionHandler((_, throwable) -> captured.complete(throwable));
        var failure = new RuntimeException("boom");

        executor.execute(() -> {
            throw failure;
        });

        assertThat(captured).succeedsWithin(Duration.ofSeconds(10)).isSameAs(failure);
    }
}
