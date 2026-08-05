package net.yudichev.jiotty.connector.octopusenergy.priceforecast;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.app.Application;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.CompletableFutures.logErrorOnFailure;

@SuppressWarnings("UseOfSystemOutOrSystemErr")
final class PriceForecastSourcesManualRunner {
    static void main() {
        Application.builder()
                   .addModule(() -> PriceForecastSourcesModule.builder().build())
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

        /// Resolved in [#doStart()]: the sources provider refuses lookups until it is started, which happens after every component is provisioned.
        private final Provider<List<PriceForecastSource>> sourcesProvider;

        @Inject
        public CmdLineTest(Provider<List<PriceForecastSource>> sourcesProvider) {
            this.sourcesProvider = checkNotNull(sourcesProvider);
        }

        @Override
        protected void doStart() {
            for (PriceForecastSource source : sourcesProvider.get()) {
                source.getPrices("C", 14)
                      .whenComplete(logErrorOnFailure(logger, "{} failed", source.name()))
                      .thenAccept(prices -> System.out.println(source.name() + " PRICES: " + prices));
            }
        }
    }
}
