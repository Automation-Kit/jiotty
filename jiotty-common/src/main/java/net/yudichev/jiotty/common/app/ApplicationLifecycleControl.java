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
    };

    void initiateShutdown();

    void initiateRestart();

    /// @return whether the application restart has been [initiated](#initiateRestart())
    boolean restarting();
}
