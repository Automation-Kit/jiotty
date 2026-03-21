package net.yudichev.jiotty.connector.google.maps;

import jakarta.inject.Inject;
import net.yudichev.jiotty.common.app.Application;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.lang.Either;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;

import static java.time.temporal.ChronoUnit.HOURS;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

final class LocalRoutesServiceRunner {
    private static final Logger logger = LogManager.getLogger(LocalRoutesServiceRunner.class);

    static void main(String[] args) {
        Application.builder()
                   .addModule(ExecutorModule::new)
                   .addModule(() -> RoutesServiceModule.builder()
                                                       .setApiKey(literally(args[0]))
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

        private final RoutesService service;

        @Inject
        public Runner(RoutesService service) {
            this.service = service;
        }

        @Override
        protected void doStart() {
            service.computeRoutes(RouteParameters.builder()
                                                 .setOriginLocation(Either.left("Westminster Abbey"))
                                                 .setDestinationLocation(Either.left("London Bridge"))
                                                 .setDepartureTime(Instant.now().plus(1, HOURS))
                                                 .build())
                   .whenComplete((routes, throwable) -> {
                       logger.info("Routes: {}", routes, throwable);
                       var thread = new Thread(() -> System.exit(0));
                       thread.setDaemon(true);
                       thread.start();
                   });
        }
    }
}
