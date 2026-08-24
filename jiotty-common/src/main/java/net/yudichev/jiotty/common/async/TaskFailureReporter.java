package net.yudichev.jiotty.common.async;

import java.util.function.BiConsumer;

/// Reporting half of [TaskExceptionHandlerRegistry], for components that guard their own work or otherwise want to report unexpected failures to the host
/// application's registered [handler][TaskExceptionHandlerRegistry#addExceptionHandler(BiConsumer)].
public interface TaskFailureReporter {
    /// @param taskDescription names the work in the failure report an operator reads, in the words of what was being done
    /// @implSpec **Never throws.** Reporting sites call this from catch blocks that go on to rethrow the failure being reported or to complete a request, so a
    /// handler that throws is contained and logged here, leaving the failure that prompted the report intact.
    void onTaskException(String taskDescription, Throwable exception);
}
