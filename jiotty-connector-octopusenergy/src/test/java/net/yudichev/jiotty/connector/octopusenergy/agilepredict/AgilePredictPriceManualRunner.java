package net.yudichev.jiotty.connector.octopusenergy.agilepredict;

import jakarta.inject.Inject;
import net.yudichev.jiotty.common.app.Application;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.lang.CompletableFutures;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@SuppressWarnings("UseOfSystemOutOrSystemErr")
final class AgilePredictPriceManualRunner {
    static void main(String[] args) {
        Application.builder()
                   .addModule(AgilePredictPriceModule::new)
                   .addModule(() -> new BaseLifecycleComponentModule() {
                       @Override
                       protected void configure() {
                           registerLifecycleComponent(CmdLineTest.class);
                       }
                   })
                   .build()
                   .run();
    }

    private static class CmdLineTest extends BaseLifecycleComponent {
        private static final Logger logger = LogManager.getLogger(CmdLineTest.class);

        private final AgilePredictPriceService service;

        @Inject
        public CmdLineTest(AgilePredictPriceService service) {
            this.service = service;
        }

        @Override
        protected void doStart() {
            service.getPrices("C", 14).whenComplete(CompletableFutures.logErrorOnFailure(logger, "error"))
                   .thenAccept(prices -> System.out.println("PRICES: " + prices));
        }
    }
}