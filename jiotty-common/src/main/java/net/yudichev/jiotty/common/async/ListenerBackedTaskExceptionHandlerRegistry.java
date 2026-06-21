package net.yudichev.jiotty.common.async;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Listeners;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.BiConsumer;

import static com.google.common.base.Preconditions.checkNotNull;

public final class ListenerBackedTaskExceptionHandlerRegistry implements TaskExceptionHandlerRegistry {
    private static final Logger logger = LogManager.getLogger(ListenerBackedTaskExceptionHandlerRegistry.class);

    private final Listeners<TaskFailure> listeners = new Listeners<>();

    @Override
    public Closeable addExceptionHandler(BiConsumer<String, Throwable> handler) {
        checkNotNull(handler);
        return listeners.addListener(taskFailure -> handler.accept(taskFailure.taskDescription(), taskFailure.exception()));
    }

    public void onTaskException(String taskDescription, Throwable exception) {
        if (listeners.isEmpty()) {
            // Nobody is escalating, so the failure would otherwise vanish: log it. A registered handler takes over reporting instead.
            logger.error("Failed while {}", taskDescription, exception);
        } else {
            listeners.notify(new TaskFailure(taskDescription, exception));
        }
    }

    private record TaskFailure(String taskDescription, Throwable exception) {}
}
