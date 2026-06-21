package net.yudichev.jiotty.common.async;

import net.yudichev.jiotty.common.lang.Closeable;

import java.util.function.BiConsumer;

/// Registry of handlers notified when a task submitted to an executor (see [SchedulingExecutor]) fails with an uncaught exception. With no handler registered
/// the failure is logged; a registered handler takes over reporting it (e.g. raising an operator alert) and the failure is then not also logged — handle it
/// or log it, never both. Registration is opt-in.
public interface TaskExceptionHandlerRegistry {
    /// Registers a handler invoked with the task description and the uncaught throwable whenever a task fails. Returns a [Closeable] that deregisters it.
    Closeable addExceptionHandler(BiConsumer<String, Throwable> handler);
}
