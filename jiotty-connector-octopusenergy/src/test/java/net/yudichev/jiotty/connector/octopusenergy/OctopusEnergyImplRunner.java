package net.yudichev.jiotty.connector.octopusenergy;

import net.yudichev.jiotty.common.time.TimeProvider;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/// manual test only
final class OctopusEnergyImplRunner {
    @SuppressWarnings({"UseOfSystemOutOrSystemErr", "CallToPrintStackTrace"})
    static void main(String[] args) {
        var oe = new OctopusEnergyImpl(new TimeProvider(), args[0], args[1]);
        oe.start();
        oe.subscribeToApiKeyState(apiKeyState -> System.out.println("KEY STATE -> " + apiKeyState));
        oe.getAgilePrices(Instant.now().minus(6, ChronoUnit.DAYS), Instant.now().plus(1, ChronoUnit.DAYS))
          .whenComplete((value, throwable) -> {
              if (value != null) {
                  for (StandardUnitRate standardUnitRate : value) {
                      System.out.println(standardUnitRate);
                  }
              } else {
                  throwable.printStackTrace();
              }
          });
    }
}