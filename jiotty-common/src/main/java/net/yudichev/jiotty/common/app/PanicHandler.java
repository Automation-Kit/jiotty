package net.yudichev.jiotty.common.app;

/// What an [Application] does when it panics, once it has discarded the tasks its executors had queued. Called once per start.
public interface PanicHandler {
    /// @param reason why, naming the executor whose queue filled up
    /// @implSpec runs on whichever thread panicked the application, which can be a producer's thread or one holding a lock, so it must not block, and must
    ///           not call [Application#start()], [Application#stop()] or [Application#close()]: a stop of the panicking application from a thread one of its
    ///           components holds a lock on deadlocks against that component's stop
    void onPanic(String reason, Throwable cause);
}
