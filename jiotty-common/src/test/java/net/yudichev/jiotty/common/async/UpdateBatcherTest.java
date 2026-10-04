package net.yudichev.jiotty.common.async;

import net.yudichev.jiotty.common.lang.MutableReference;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpdateBatcherTest {
    private final ProgrammableClock clock = new ProgrammableClock();
    private final RejectingSchedulingExecutor executor = new RejectingSchedulingExecutor(clock.createSingleThreadedSchedulingExecutor("owner"));
    private final List<List<String>> processedBatches = new ArrayList<>();
    private final List<List<String>> batchesAfterClose = new ArrayList<>();
    private final UpdateBatcher<List<String>> batcher =
            new UpdateBatcher<>(executor, "process updates", ArrayList::new, processedBatches::add, batchesAfterClose::add);

    @Test
    void updatesAddedBeforeTheTaskRunsAreProcessedAsOneBatch() {
        batcher.add(batch -> batch.add("a"));
        batcher.add(batch -> batch.add("b"));
        clock.tick();

        assertThat(processedBatches).containsExactly(List.of("a", "b"));
    }

    @Test
    void anUpdateAddedWhileABatchIsProcessedIsProcessedInTheNextBatch() {
        var batcherAddingFromProcessor = new MutableReference<UpdateBatcher<List<String>>>();
        batcherAddingFromProcessor.set(new UpdateBatcher<>(executor, "process updates", ArrayList::new, batch -> {
            processedBatches.add(batch);
            if (batch.contains("a")) {
                batcherAddingFromProcessor.get().add(next -> next.add("b"));
            }
        }, batchesAfterClose::add));

        batcherAddingFromProcessor.get().add(batch -> batch.add("a"));
        clock.tick();

        assertThat(processedBatches).containsExactly(List.of("a"), List.of("b"));
    }

    @Test
    void anUpdateWhoseTaskIsRejectedIsProcessedWithTheNextUpdate() {
        executor.fillQueue();
        assertThatThrownBy(() -> batcher.add(batch -> batch.add("a"))).isInstanceOf(RejectedExecutionException.class);
        executor.emptyQueue();

        batcher.add(batch -> batch.add("b"));
        clock.tick();

        assertThat(processedBatches).containsExactly(List.of("a", "b"));
    }

    @Test
    void closeHandsThePendingBatchToTheAfterCloseHandler() {
        batcher.add(batch -> batch.add("a"));

        batcher.close();

        assertThat(batchesAfterClose).containsExactly(List.of("a"));
    }

    @Test
    void anUpdateAddedAfterCloseGoesToTheAfterCloseHandlerOnTheAddingThread() {
        batcher.close();

        batcher.add(batch -> batch.add("a"));

        assertThat(batchesAfterClose).containsExactly(List.of("a"));
        assertThat(processedBatches).isEmpty();
    }

    /// The executor can shut down before the owner closes the batcher.
    @Test
    void anUpdateAddedOnceTheExecutorHasShutDownGoesToTheAfterCloseHandler() {
        executor.close();

        batcher.add(batch -> batch.add("a"));
        batcher.add(batch -> batch.add("b"));

        assertThat(batchesAfterClose).containsExactly(List.of("a"), List.of("b"));
        assertThat(processedBatches).isEmpty();
    }

    @Test
    void aTaskQueuedBeforeCloseProcessesNothing() {
        batcher.add(batch -> batch.add("a"));
        batcher.close();

        clock.tick();

        assertThat(processedBatches).isEmpty();
        assertThat(batchesAfterClose).containsExactly(List.of("a"));
    }
}
