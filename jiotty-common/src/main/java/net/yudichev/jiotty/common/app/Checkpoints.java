package net.yudichev.jiotty.common.app;

/// Points in [Application#run()] at which a test holds the thread, with a latch, to reproduce one interleaving exactly. Every call is the operand of an
/// `assert`, so a build with assertions disabled makes none of them.
interface Checkpoints {
    Checkpoints NONE = new Checkpoints() {
    };

    /// On the run thread, as it is about to start the components: at the first start, and at each restart once the restart has been decided on.
    default boolean beforeStart() {
        return true;
    }

    /// On the run thread, once the components of a start have stopped and before the loop decides whether to start them again.
    default boolean afterStop() {
        return true;
    }

    /// On the requesting thread, once a restart request has been accepted and before it is acted on.
    default boolean afterRestartAccepted() {
        return true;
    }

    /// On the JVM shutdown hook's thread, once it has requested the shutdown and before it waits for the run to finish.
    default boolean afterJvmShutdownRequested() {
        return true;
    }
}
