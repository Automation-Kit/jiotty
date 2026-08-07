package net.yudichev.jiotty.connector.world.weather;

import jakarta.inject.Inject;
import net.yudichev.jiotty.common.app.Application;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.geo.LatLon;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.time.TimeModule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

@SuppressWarnings({"UseOfSystemOutOrSystemErr", "CallToSystemExit"})
final class ManualWeatherServiceRunner {
    static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: ManualWeatherServiceRunner <apiKey>");
            System.exit(2);
        }
        var apiKey = args[0];

        Application.builder()
                   .addModule(() -> ExecutorModule.builder().build())
                   .addModule(() -> TimeModule.builder().build())
                   .addModule(() -> WeatherServiceModule.builder()
                                                        .setApiKey(literally(apiKey))
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
    private static class Runner extends BaseLifecycleComponent {
        private static final Logger logger = LogManager.getLogger(Runner.class);

        private final WeatherService weatherService;

        @Inject
        Runner(WeatherService weatherService) {
            this.weatherService = weatherService;
        }

        @Override
        protected void doStart() {
            var coordinates = new LatLon(51.5074, -0.1278); // London

            var currentFuture = weatherService.getCurrentWeather(coordinates)
                                              .whenComplete((current, error) -> {
                                                  if (error != null) {
                                                      logger.error("Failed to get current weather", error);
                                                  } else {
                                                      logger.info("Current temperature at {}: {} C", coordinates, current.tempCelsius());
                                                  }
                                              });

            var forecastInstant = Instant.now().plus(WeatherService.MAX_FORECAST_DAYS, ChronoUnit.DAYS);
            var forecastFuture = weatherService.getForecastWeather(coordinates, forecastInstant)
                                               .whenComplete((forecast, error) -> {
                                                   if (error != null) {
                                                       logger.error("Failed to get forecast weather for {}", forecastInstant, error);
                                                   } else {
                                                       logger.info("Forecast temperature at {} for {}: {}", coordinates, forecastInstant, forecast);
                                                   }
                                               });

            CompletableFuture.allOf(currentFuture, forecastFuture)
                             .whenComplete((ignored, error) -> {
                                 var thread = new Thread(() -> System.exit(error == null ? 0 : 1));
                                 thread.setDaemon(true);
                                 thread.start();
                             });
        }
    }
}
