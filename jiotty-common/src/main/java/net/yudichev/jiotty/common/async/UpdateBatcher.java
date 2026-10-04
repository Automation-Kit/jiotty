package net.yudichev.jiotty.common.async;

import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkNotNull;

/// Collects updates added on any thread into one batch, processed by a single task on the executor however many updates arrive before that task runs. Close
/// it on the executor's thread: the batch pending then, every update added afterwards, and every batch once the executor has shut down go to the after-close
/// handler instead.
///
/// @param <B> the batch, which must stay bounded however many updates are added, for example by keeping the latest value per key
public final class UpdateBatcher<B> extends BaseIdempotentCloseable {
    private final TaskExecutor executor;
    private final String taskName;
    private final Supplier<? extends B> batchFactory;
    private final Consumer<? super B> batchProcessor;
    private final Consumer<? super B> afterCloseHandler;
    /// Guards the pending batch, which [#add] writes on the adding thread and the queued task takes on the executor's thread.
    private final Object lock = new Object();
    private @Nullable B pendingBatch;
    private boolean taskQueued;

    /// @param batchProcessor    runs on `executor` with each batch
    /// @param afterCloseHandler runs with the batch pending at close, on the closing thread, and on the adding thread with each batch that can no longer be
    ///                          processed: an update added after close, or once `executor` has shut down
    public UpdateBatcher(TaskExecutor executor,
                         String taskName,
                         Supplier<? extends B> batchFactory,
                         Consumer<? super B> batchProcessor,
                         Consumer<? super B> afterCloseHandler) {
        this.executor = checkNotNull(executor, "executor");
        this.taskName = checkNotNull(taskName, "taskName");
        this.batchFactory = checkNotNull(batchFactory, "batchFactory");
        this.batchProcessor = checkNotNull(batchProcessor, "batchProcessor");
        this.afterCloseHandler = checkNotNull(afterCloseHandler, "afterCloseHandler");
    }

    @Override
    protected void doClose() {
        handPendingBatchToAfterCloseHandler();
    }

    /// Adds an update to the pending batch, and queues the task that processes the batch unless it is already queued.
    ///
    /// @param update writes the update into the batch; it runs while the batch is locked, so it must do nothing else
    /// @throws RejectedExecutionException if the executor rejects the task; the update stays in the batch, and the next [#add] queues the task again
    public void add(Consumer<? super B> update) {
        B batchAfterClose = null;
        boolean taskToQueue = false;
        synchronized (lock) {
            if (isClosed()) {
                batchAfterClose = batchFactory.get();
                update.accept(batchAfterClose);
            } else {
                if (pendingBatch == null) {
                    pendingBatch = batchFactory.get();
                }
                update.accept(pendingBatch);
                taskToQueue = !taskQueued;
                taskQueued = true;
            }
        }
        if (batchAfterClose != null) {
            afterCloseHandler.accept(batchAfterClose);
        } else if (taskToQueue) {
            boolean queued;
            try {
                queued = executor.tryExecute(taskName, this::processPendingBatch);
            } catch (RejectedExecutionException e) {
                synchronized (lock) {
                    taskQueued = false;
                }
                throw e;
            }
            if (!queued) {
                handPendingBatchToAfterCloseHandler();
            }
        }
    }

    private void handPendingBatchToAfterCloseHandler() {
        B batch;
        synchronized (lock) {
            batch = pendingBatch;
            pendingBatch = null;
            taskQueued = false;
        }
        if (batch != null) {
            afterCloseHandler.accept(batch);
        }
    }

    private void processPendingBatch() {
        B batch;
        synchronized (lock) {
            taskQueued = false;
            batch = pendingBatch;
            pendingBatch = null;
        }
        if (batch != null) {
            batchProcessor.accept(batch);
        }
    }
}
