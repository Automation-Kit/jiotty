package net.yudichev.jiotty.connector.tplinksmartplug;

import jakarta.inject.Inject;
import net.yudichev.jiotty.appliance.Appliance;
import net.yudichev.jiotty.common.app.Application;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

final class LocalTpLinkSmartPlugRunner {
    static void main(String[] args) {
        Application.builder()
                   .addModule(() -> ExecutorModule.builder().build())
                   .addModule(() -> TpLinkSmartPlugModule.localConnectionBuilder()
                                                         .setHost(literally(args[0]))
                                                         .build())
                   .addModule(() -> new BaseLifecycleComponentModule() {
                       @Override
                       protected void configure() {
                           registerLifecycleComponent(Runner.class);
                       }
                   })
                   .build()
                   .run();
    }

    @SuppressWarnings("CallToSystemExit")
    static class Runner extends BaseLifecycleComponent {
        private final Appliance appliance;

        @Inject
        public Runner(Appliance appliance) {
            this.appliance = checkNotNull(appliance);
        }

        @Override
        protected void doStart() {
            appliance.turnOn();
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            appliance.turnOff();
            var thread = new Thread(() -> System.exit(0));
            thread.setDaemon(true);
            thread.start();
        }
    }
}
