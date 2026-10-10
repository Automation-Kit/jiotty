package net.yudichev.jiotty.common.app;

public interface ApplicationLifecycleControl {
    ApplicationLifecycleControl NOOP = new ApplicationLifecycleControl() {
        @Override
        public void initiateShutdown() {
        }

        @Override
        public void initiateRestart() {
        }

        @Override
        public boolean restarting() {
            return false;
        }

        @Override
        public void panic(String reason, Throwable cause) {
        }
    };

    void initiateShutdown();

    void initiateRestart();

    /// @return whether the application restart has been [initiated](#initiateRestart())
    boolean restarting();

    /// Panics the application as a full queue on one of its own executors does: its executors' queued tasks are discarded and its [PanicHandler] runs, once
    /// per start. Safe to call from any thread, and does not block.
    void panic(String reason, Throwable cause);
}
