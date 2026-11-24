package net.yudichev.jiotty.connector.octopusenergy;

import net.yudichev.jiotty.common.async.ListenerBackedTaskExceptionHandlerRegistry;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;
import net.yudichev.jiotty.common.misc.UpstreamHealthHandler;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/// Manual smoke test against the live Octopus API. Not run by CI — invoked from an IDE run configuration.
///
/// **Rule of thumb when adding a new endpoint to [OctopusEnergy], [OctopusRegionService], or [OctopusAccountService]: exercise it here so the manual smoke run
/// still covers every public surface.**
@SuppressWarnings({"UseOfSystemOutOrSystemErr", "CallToPrintStackTrace"})
final class ManualOctopusEnergyRunner {
    static final OctopusEnergyImpl oe = new OctopusEnergyImpl(UpstreamHealthHandler.NO_OP,
                                                              RetryableOperationExecutor.noRetries(),
                                                              new ListenerBackedTaskExceptionHandlerRegistry());

    static void main(String[] args) {
        // args: [0]=accountId, [1]=apiKey, [2]=productCode, [3]=tariffCode (e.g. "E-1R-AGILE-23-12-06-A")
        oe.start();

        // Open endpoints (no auth) — exercise these regardless of whether credentials were supplied.
        oe.listProducts(Instant.now()).whenComplete((products, throwable) -> {
            if (products != null) {
                System.out.println("PRODUCTS (" + products.size() + "):");
                products.forEach(p -> System.out.println("  " + p.code() + " " + p.displayName() + " brand=" + p.brand()));
            } else {
                throwable.printStackTrace();
            }
        });

        String productCode = args[2];
        getProductDetails(productCode);

        try (var account = oe.account(args[0], args[1])) {
            account.subscribeToAuthState(authState -> System.out.println("AUTH STATE -> " + authState));
            account.getMpanAndMeter().whenComplete((rows, throwable) -> {
                if (rows != null) {
                    System.out.println("MPAN+METER pairs (" + rows.size() + "):");
                    // Print the raw values — toString() is redacted per Task-3 policy.
                    rows.forEach(row -> System.out.println("  mpan=" + row.mpan() + " serial=" + row.meterSerial()));
                    // Exercise the consumption endpoint against the first meter (if any).
                    rows.forEach(row -> getConsumption(account, row));
                } else {
                    throwable.printStackTrace();
                }
            });
            account.getAccount().whenComplete((data, throwable) -> {
                if (data != null) {
                    System.out.println("ACCOUNT: " + data);
                    // Print MPAN/meter serial directly — `toString()` redacts both per the Task-3 PII policy, but the raw fields are needed for the
                    // human running this to confirm the new Task-4 types parsed correctly from the live payload.
                    data.properties().forEach(property ->
                                                      property.electricityMeterPoints().forEach(meterPoint -> {
                                                          System.out.println("MPAN (raw): " + meterPoint.mpan());
                                                          meterPoint.meters()
                                                                    .forEach(meter -> System.out.println("METER serial (raw): " + meter.serialNumber()));
                                                          meterPoint.tariffs().forEach(tariff -> {System.out.println("TARIFF: " + tariff);});
                                                      }));
                } else {
                    throwable.printStackTrace();
                }
            });

            getRates(productCode, args[3]);
        }
    }

    private static void getRates(String productCode, String tariffCode) {
        char regionLetter = tariffCode.charAt(tariffCode.length() - 1);
        Instant from = Instant.now().minus(6, ChronoUnit.DAYS);
        Instant to = Instant.now().plus(1, ChronoUnit.DAYS);
        try (OctopusRegionService region = oe.region(regionLetter)) {
            print("STANDARD UNIT RATES", region.getStandardUnitRates(productCode, tariffCode, from, to));
            // Octopus serve these two only for a tariff with separate day and night rates, one listed under the product's
            // dual_register_electricity_tariffs or four_rate_ev_electricity_tariffs (such as E-1R-IOG-SMB-VAR-26-11-04-K), and fail them for any other.
            print("DAY UNIT RATES", region.getDayUnitRates(productCode, tariffCode, from, to));
            print("NIGHT UNIT RATES", region.getNightUnitRates(productCode, tariffCode, from, to));
            print("STANDING CHARGES", region.getStandingCharges(productCode, tariffCode, from, to));
        }
    }

    private static void print(String heading, CompletableFuture<? extends List<?>> rowsFuture) {
        rowsFuture.whenComplete((value, throwable) -> {
            if (value != null) {
                System.out.println(heading + " (" + value.size() + "):");
                value.forEach(System.out::println);
            } else {
                throwable.printStackTrace();
            }
        });
    }

    private static void getConsumption(OctopusAccountService account, MpanAndMeter pair) {
        // Last 24 hours of half-hour slots — small window so the smoke run stays fast.
        Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant to = Instant.now();
        account.getConsumption(pair.mpan(), pair.meterSerial(), from, to)
               .whenComplete((rows, throwable) -> {
                   if (rows != null) {
                       System.out.println("CONSUMPTION rows (" + rows.size() + ") for mpan=" + pair.mpan() + ":");
                       rows.forEach(System.out::println);
                   } else {
                       throwable.printStackTrace();
                   }
               });
    }

    private static void getProductDetails(String productCode) {
        oe.getProductDetails(productCode).whenComplete((details, throwable) -> {
            if (details != null) {
                System.out.println("PRODUCT DETAILS: " + details);
            } else {
                throwable.printStackTrace();
            }
        });
    }
}
