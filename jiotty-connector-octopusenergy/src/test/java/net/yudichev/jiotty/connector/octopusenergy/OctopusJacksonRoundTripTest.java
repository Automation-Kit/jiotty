package net.yudichev.jiotty.connector.octopusenergy;

import net.yudichev.jiotty.common.lang.Json;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/// Per-type Jackson round-trip coverage for the new Task-4 types. Fixtures use the canonical sanitisation tokens from the Task-3 policy (account id
/// `A-AAAAAAAA`, MPAN `9999999999999`, meter serial `99XXX99999`).
class OctopusJacksonRoundTripTest {

    @Test
    void product_parsesMinimalSummaryAndPreservesLinks() {
        Product product = Json.parse("""
                                     {
                                       "code": "AGILE-23-12-06",
                                       "direction": "IMPORT",
                                       "full_name": "Octopus Agile December 2023 v1",
                                       "display_name": "Agile Octopus",
                                       "description": "...",
                                       "is_variable": true,
                                       "is_green": true,
                                       "is_tracker": false,
                                       "is_prepay": false,
                                       "is_business": false,
                                       "is_restricted": false,
                                       "term": null,
                                       "available_from": "2023-12-06T00:00:00Z",
                                       "available_to": null,
                                       "brand": "OCTOPUS_ENERGY",
                                       "links": [
                                         {"href": "https://api.octopus.energy/v1/products/AGILE-23-12-06/", "method": "GET", "rel": "self"}
                                       ]
                                     }
                                     """, Product.class);

        assertThat(product.code()).isEqualTo("AGILE-23-12-06");
        assertThat(product.displayName()).isEqualTo("Agile Octopus");
        assertThat(product.brand()).isEqualTo("OCTOPUS_ENERGY");
        assertThat(product.availableFrom()).isEqualTo(Instant.parse("2023-12-06T00:00:00Z"));
        assertThat(product.availableTo()).isEmpty();
        assertThat(product.links()).singleElement().satisfies(link -> {
            assertThat(link.href()).isEqualTo("https://api.octopus.energy/v1/products/AGILE-23-12-06/");
            assertThat(link.method()).isEqualTo("GET");
            assertThat(link.rel()).isEqualTo("self");
        });
    }

    @Test
    void productDetails_parsesPerRegionPerPaymentMethodTariffMatrix() {
        ProductDetails details = Json.parse("""
                                            {
                                              "code": "AGILE-23-12-06",
                                              "display_name": "Agile Octopus",
                                              "brand": "OCTOPUS_ENERGY",
                                              "available_from": "2023-12-06T00:00:00Z",
                                              "available_to": null,
                                              "tariffs_active_at": "2024-01-01T12:00:00Z",
                                              "single_register_electricity_tariffs": {
                                                "_A": {
                                                  "direct_debit_monthly": {
                                                    "code": "E-1R-AGILE-23-12-06-A",
                                                    "standing_charge_exc_vat": 42.5,
                                                    "standing_charge_inc_vat": 44.625,
                                                    "links": []
                                                  }
                                                }
                                              }
                                            }
                                            """, ProductDetails.class);

        assertThat(details.code()).isEqualTo("AGILE-23-12-06");
        assertThat(details.tariffsActiveAt()).isEqualTo(Instant.parse("2024-01-01T12:00:00Z"));
        assertThat(details.tracker()).isFalse();
        assertThat(details.fourRateEvElectricityTariffs()).isEmpty();
        assertThat(details.singleRegisterElectricityTariffs())
                .hasEntrySatisfying("_A", regionEntry ->
                        assertThat(regionEntry).hasEntrySatisfying("direct_debit_monthly", variant -> {
                            assertThat(variant.code()).isEqualTo("E-1R-AGILE-23-12-06-A");
                            assertThat(variant.standingChargeIncVat()).isEqualTo(44.625);
                            assertThat(variant.standingChargeExcVat()).isEqualTo(42.5);
                        }));
    }

    @Test
    void consumptionRow_parsesHalfHourSlot() {
        ConsumptionRow row = Json.parse("""
                                        {"consumption": 0.345, "interval_start": "2024-01-15T00:00:00Z", "interval_end": "2024-01-15T00:30:00Z"}
                                        """, ConsumptionRow.class);

        assertThat(row.consumption()).isEqualTo(0.345);
        assertThat(row.intervalStart()).isEqualTo(Instant.parse("2024-01-15T00:00:00Z"));
        assertThat(row.intervalEnd()).isEqualTo(Instant.parse("2024-01-15T00:30:00Z"));
    }

    @Test
    void standingCharge_parsesValidityWindow() {
        StandingCharge charge = Json.parse("""
                                           {
                                             "value_exc_vat": 47.62,
                                             "value_inc_vat": 50.0,
                                             "valid_from": "2024-01-01T00:00:00Z",
                                             "valid_to": "2024-12-31T23:59:59Z"
                                           }
                                           """, StandingCharge.class);

        assertThat(charge.valueExcVat()).isEqualTo(47.62);
        assertThat(charge.valueIncVat()).isEqualTo(50.0);
        assertThat(charge.validFrom()).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"));
        assertThat(charge.validTo()).contains(Instant.parse("2024-12-31T23:59:59Z"));
    }

    @Test
    void standingCharge_openEndedValidToIsEmptyOptional() {
        // The Octopus API returns `"valid_to": null` for the currently-active charge — pin the Optional shape so future readers don't accidentally
        // re-tighten validTo() back to a required Instant (which was the original bug).
        StandingCharge charge = Json.parse("""
                                           {
                                             "value_exc_vat": 46.4649,
                                             "value_inc_vat": 48.788145,
                                             "valid_from": "2024-09-30T23:00:00Z",
                                             "valid_to": null,
                                             "payment_method": null
                                           }
                                           """, StandingCharge.class);

        assertThat(charge.validFrom()).isEqualTo(Instant.parse("2024-09-30T23:00:00Z"));
        assertThat(charge.validTo()).isEmpty();
    }

    @Test
    void tariff_openEndedValidToIsEmptyOptional() {
        // Octopus lists the variable tariff that follows a fixed-term agreement with `"valid_to": null`.
        Tariff tariff = Json.parse("""
                                   {"tariff_code": "E-1R-VAR-22-11-01-A", "valid_from": "2026-11-21T00:00:00Z", "valid_to": null}
                                   """, Tariff.class);

        assertThat(tariff.validFrom()).isEqualTo(Instant.parse("2026-11-21T00:00:00Z"));
        assertThat(tariff.validTo()).isEmpty();
    }

    @Test
    void standardUnitRate_openEndedValidToIsEmptyOptional() {
        // The rate in force on a variable tariff has no end date yet.
        StandardUnitRate rate = Json.parse("""
                                           {"value_exc_vat": 23.1, "value_inc_vat": 24.255, "valid_from": "2026-10-01T00:00:00Z", "valid_to": null}
                                           """, StandardUnitRate.class);

        assertThat(rate.validFrom()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(rate.validTo()).isEmpty();
        assertThat(rate.paymentMethod()).isEmpty();
    }

    @Test
    void standardUnitRate_parsesPaymentMethod() {
        StandardUnitRate rate = Json.parse("""
                                           {"value_exc_vat": 26.554, "value_inc_vat": 26.554, "valid_from": "2026-09-30T23:00:00Z", "valid_to": null,
                                            "payment_method": "DIRECT_DEBIT"}
                                           """, StandardUnitRate.class);

        assertThat(rate.paymentMethod()).contains("DIRECT_DEBIT");
    }

    @Test
    void standingCharge_parsesPaymentMethod() {
        StandingCharge charge = Json.parse("""
                                           {"value_exc_vat": 61.74, "value_inc_vat": 61.74, "valid_from": "2026-09-30T23:00:00Z", "valid_to": null,
                                            "payment_method": "NON_DIRECT_DEBIT"}
                                           """, StandingCharge.class);

        assertThat(charge.paymentMethod()).contains("NON_DIRECT_DEBIT");
    }

    @Test
    void tariff_ignoresAnUnknownProperty() {
        Tariff tariff = Json.parse("""
                                   {"tariff_code": "E-1R-VAR-22-11-01-A", "valid_from": "2026-11-21T00:00:00Z", "valid_to": null, "new_field": 1}
                                   """, Tariff.class);

        assertThat(tariff.tariffCode()).isEqualTo("E-1R-VAR-22-11-01-A");
    }

    @Test
    void productDetails_parsesFourRateTariffsAndTheTrackerFlag() {
        ProductDetails details = Json.parse("""
                                            {
                                              "code": "IOG-SMB-VAR-26-11-04",
                                              "display_name": "Intelligent Octopus Go",
                                              "brand": "OCTOPUS_ENERGY",
                                              "is_tracker": false,
                                              "available_from": "2026-11-05T00:00:00Z",
                                              "available_to": null,
                                              "tariffs_active_at": "2026-11-21T12:00:00Z",
                                              "single_register_electricity_tariffs": {},
                                              "dual_register_electricity_tariffs": {},
                                              "four_rate_ev_electricity_tariffs": {
                                                "_A": {
                                                  "direct_debit_monthly": {
                                                    "code": "E-1R-IOG-SMB-VAR-26-11-04-A",
                                                    "standing_charge_exc_vat": 51.94,
                                                    "standing_charge_inc_vat": 51.94,
                                                    "links": [],
                                                    "day_unit_rate_inc_vat": 34.4638,
                                                    "night_unit_rate_inc_vat": 9.9
                                                  }
                                                }
                                              }
                                            }
                                            """, ProductDetails.class);

        assertThat(details.tracker()).isFalse();
        assertThat(details.singleRegisterElectricityTariffs()).isEmpty();
        assertThat(details.dualRegisterElectricityTariffs()).isEmpty();
        assertThat(details.fourRateEvElectricityTariffs())
                .hasEntrySatisfying("_A", regionEntry ->
                        assertThat(regionEntry).hasEntrySatisfying("direct_debit_monthly",
                                                                   variant -> assertThat(variant.code()).isEqualTo("E-1R-IOG-SMB-VAR-26-11-04-A")));
    }

    @Test
    void productDetails_readsTheTrackerFlag() {
        ProductDetails details = Json.parse("""
                                            {
                                              "code": "SILVER-24-12-31",
                                              "display_name": "Octopus Tracker",
                                              "brand": "OCTOPUS_ENERGY",
                                              "is_tracker": true,
                                              "available_from": "2024-12-31T00:00:00Z",
                                              "available_to": null,
                                              "tariffs_active_at": "2026-11-21T12:00:00Z",
                                              "single_register_electricity_tariffs": {}
                                            }
                                            """, ProductDetails.class);

        assertThat(details.tracker()).isTrue();
    }

    @Test
    void accountData_parsesAnAccountWhoseLastAgreementIsOpenEnded() {
        OctopusAccountData account = Json.parse("""
                                                {
                                                  "number": "A-AAAAAAAA",
                                                  "properties": [{
                                                    "id": 1,
                                                    "moved_in_at": "2025-06-18T00:00:00+01:00",
                                                    "moved_out_at": null,
                                                    "address_line_1": "1 EXAMPLE STREET",
                                                    "town": "EXAMPLETON",
                                                    "postcode": "ZZ99 9ZZ",
                                                    "electricity_meter_points": [{
                                                      "mpan": "9999999999999",
                                                      "meters": [{"serial_number": "99XXX99999"}],
                                                      "agreements": [
                                                        {"tariff_code": "E-1R-AGILE-24-10-01-A",
                                                         "valid_from": "2025-11-21T00:00:00Z", "valid_to": "2026-11-21T00:00:00Z"},
                                                        {"tariff_code": "E-1R-VAR-22-11-01-A", "valid_from": "2026-11-21T00:00:00Z", "valid_to": null}
                                                      ],
                                                      "is_export": false
                                                    }],
                                                    "gas_meter_points": []
                                                  }]
                                                }
                                                """, OctopusAccountData.class);

        assertThat(account.properties().getFirst().electricityMeterPoints().getFirst().tariffs())
                .extracting(Tariff::validTo)
                .containsExactly(Optional.of(Instant.parse("2026-11-21T00:00:00Z")), Optional.empty());
    }

    @Test
    void electricityMeter_parsesSerialNumberAndRedactsIt() {
        ElectricityMeter meter = Json.parse("""
                                            {"serial_number": "99XXX99999", "is_smart_import_electricity_meter": false}
                                            """, ElectricityMeter.class);

        assertThat(meter.serialNumber()).isEqualTo("99XXX99999");
        assertThat(meter.toString()).doesNotContain("99XXX99999");
    }

    @Test
    void productsPage_parsesPaginationWrapperWithNextUrl() {
        ProductsPage page = Json.parse("""
                                       {
                                         "count": 92,
                                         "next": "https://api.octopus.energy/v1/products/?page=2",
                                         "previous": null,
                                         "results": [
                                           {
                                             "code": "AGILE-23-12-06",
                                             "display_name": "Agile Octopus",
                                             "full_name": "Octopus Agile December 2023 v1",
                                             "available_from": "2023-12-06T00:00:00Z",
                                             "available_to": null,
                                             "brand": "OCTOPUS_ENERGY",
                                             "links": []
                                           }
                                         ]
                                       }
                                       """, ProductsPage.class);

        assertThat(page.nextUrl()).contains("https://api.octopus.energy/v1/products/?page=2");
        assertThat(page.results()).singleElement().satisfies(p -> assertThat(p.code()).isEqualTo("AGILE-23-12-06"));
    }

    @Test
    void productsPage_parsesLastPageWithNullNext() {
        ProductsPage page = Json.parse("""
                                       {"count": 1, "next": null, "previous": null, "results": []}
                                       """, ProductsPage.class);
        assertThat(page.nextUrl()).isEmpty();
        assertThat(page.results()).isEmpty();
    }

    @Test
    void standingChargesPage_parsesPaginationWrapper() {
        StandingChargesPage page = Json.parse("""
                                              {
                                                "count": 1, "next": null, "previous": null,
                                                "results": [
                                                  {"value_exc_vat": 47.62, "value_inc_vat": 50.0,
                                                   "valid_from": "2024-01-01T00:00:00Z", "valid_to": "2024-12-31T23:59:59Z"}
                                                ]
                                              }
                                              """, StandingChargesPage.class);

        assertThat(page.nextUrl()).isEmpty();
        assertThat(page.results()).singleElement().satisfies(c -> assertThat(c.valueIncVat()).isEqualTo(50.0));
    }

    @Test
    void consumptionPage_parsesPaginationWrapper() {
        ConsumptionPage page = Json.parse("""
                                          {
                                            "count": 2, "next": "https://api.octopus.energy/v1/.../consumption/?page=2", "previous": null,
                                            "results": [
                                              {"consumption": 0.345, "interval_start": "2024-01-15T00:00:00Z", "interval_end": "2024-01-15T00:30:00Z"},
                                              {"consumption": 0.422, "interval_start": "2024-01-15T00:30:00Z", "interval_end": "2024-01-15T01:00:00Z"}
                                            ]
                                          }
                                          """, ConsumptionPage.class);

        assertThat(page.nextUrl()).contains("https://api.octopus.energy/v1/.../consumption/?page=2");
        assertThat(page.results()).extracting(ConsumptionRow::consumption).containsExactly(0.345, 0.422);
    }

    @Test
    void electricityMeterPoint_parsesMpanAndMetersAndRedactsMpan() {
        ElectricityMeterPoint mp = Json.parse("""
                                              {
                                                "mpan": "9999999999999",
                                                "meters": [
                                                  {"serial_number": "99XXX99999"}
                                                ],
                                                "agreements": [
                                                  {
                                                    "tariff_code": "E-1R-AGILE-23-12-06-A",
                                                    "valid_from": "2024-01-01T00:00:00Z",
                                                    "valid_to": "2099-01-01T00:00:00Z"
                                                  }
                                                ]
                                              }
                                              """, ElectricityMeterPoint.class);

        assertThat(mp.mpan()).isEqualTo("9999999999999");
        assertThat(mp.meters()).singleElement().satisfies(m -> assertThat(m.serialNumber()).isEqualTo("99XXX99999"));
        assertThat(mp.tariffs()).singleElement().satisfies(t -> assertThat(t.tariffCode()).isEqualTo("E-1R-AGILE-23-12-06-A"));
        assertThat(mp.toString()).doesNotContain("9999999999999");
        assertThat(mp.isExport()).isFalse();   // absent is_export → default false (a consumption point is never mistaken for export)
    }

    @Test
    void electricityMeterPoint_readsIsExportFlag() {
        ElectricityMeterPoint mp = Json.parse("""
                                              {
                                                "mpan": "9999999999999",
                                                "meters": [{"serial_number": "99XXX99999"}],
                                                "agreements": [],
                                                "is_export": true
                                              }
                                              """, ElectricityMeterPoint.class);

        assertThat(mp.isExport()).isTrue();
    }
}
