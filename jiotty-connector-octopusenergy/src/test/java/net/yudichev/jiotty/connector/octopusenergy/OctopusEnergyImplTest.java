package net.yudichev.jiotty.connector.octopusenergy;

import net.yudichev.jiotty.common.rest.HttpResponseException;
import net.yudichev.jiotty.common.security.AuthState;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class OctopusEnergyImplTest {

    private static final MediaType APPLICATION_JSON = MediaType.parse("application/json");
    private final Map<String, String> stubbedResponses = new HashMap<>();
    /// Overrides the default HTTP 200 for a given URL — populated by [#stubError]. Tests asserting non-2xx behaviour register the URL here and in
    /// [#stubbedResponses] (same map, the body is the error payload).
    private final Map<String, Integer> stubbedStatuses = new HashMap<>();
    private final List<Request> requestLog = new ArrayList<>();
    private OkHttpClient httpClient;
    private OctopusEnergyImpl octopusEnergy;

    @BeforeEach
    void setUp() {
        // Per-request Call mocks: each `httpClient.newCall(request)` returns a Call that, on `enqueue(callback)`, either delivers a fake 200 Response from
        // `stubbedResponses` (if the request URL is registered) or stays pending (Mockito no-op). Tests opt in to delivery by calling `stubGet(url, json)`;
        // legacy interning/eviction tests leave the map empty and their futures never complete — same behaviour as before this refactor.
        // Every Request is also recorded in `requestLog` so tests can assert headers / method / URL on what was actually sent.
        httpClient = mock(OkHttpClient.class);
        lenient().when(httpClient.newCall(any())).thenAnswer(inv -> {
            Request request = inv.getArgument(0);
            requestLog.add(request);
            Call callMock = mock(Call.class);
            lenient().when(callMock.request()).thenReturn(request);
            lenient().doAnswer(enqueueInv -> {
                Callback cb = enqueueInv.getArgument(0);
                String url = request.url().toString();
                String body = stubbedResponses.get(url);
                if (body != null) {
                    int status = stubbedStatuses.getOrDefault(url, 200);
                    cb.onResponse(callMock, fakeResponse(request, status, body));
                }
                return null;
            }).when(callMock).enqueue(any());
            return callMock;
        });
        octopusEnergy = new OctopusEnergyImpl() {
            @Override
            OkHttpClient createHttpClient() {
                return httpClient;
            }
        };
        octopusEnergy.start();
    }

    private void stubGet(String url, String responseJson) {
        stubbedResponses.put(url, responseJson);
    }

    private void stubError(String url, int status, String body) {
        stubbedResponses.put(url, body);
        stubbedStatuses.put(url, status);
    }

    private static Response fakeResponse(Request request, int status, String json) {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(status == 200 ? "OK" : "Error")
                .body(ResponseBody.create(json, APPLICATION_JSON))
                .build();
    }

    @AfterEach
    void tearDown() {
        octopusEnergy.stop();
    }

    @Test
    void region_sameLetter_returnsInternedInstance() {
        OctopusRegionService first = octopusEnergy.region('A');
        OctopusRegionService second = octopusEnergy.region('A');

        assertThat(second).isSameAs(first);
    }

    @Test
    void region_differentLetters_returnDifferentInstances() {
        assertThat(octopusEnergy.region('A')).isNotSameAs(octopusEnergy.region('B'));
    }

    @Test
    void region_closeEvictsFromCache_subsequentCallReturnsFreshInstance() {
        OctopusRegionService first = octopusEnergy.region('A');
        first.close();
        OctopusRegionService second = octopusEnergy.region('A');

        assertThat(second).isNotSameAs(first);
    }

    @ParameterizedTest
    @ValueSource(chars = {'Q', 'I', 'O', 'a', '0', ' '})
    void region_unknownOrSkippedLetter_throws(char letter) {
        // I and O are the deliberate gaps in the GB-DNO region set; anything outside A..P is unknown.
        assertThatThrownBy(() -> octopusEnergy.region(letter))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown Octopus region letter");
    }

    @Test
    void account_sameCredentials_returnsInternedInstance() {
        OctopusAccountService first = octopusEnergy.account("A-123", "key-x");
        OctopusAccountService second = octopusEnergy.account("A-123", "key-x");

        assertThat(second).isSameAs(first);
    }

    @Test
    void account_differentAccountId_returnsDifferentInstance() {
        assertThat(octopusEnergy.account("A-111", "key-x"))
                .isNotSameAs(octopusEnergy.account("A-222", "key-x"));
    }

    @Test
    void account_differentApiKey_returnsDifferentInstance() {
        // The api-key is part of the cache key — two callers with the same accountId but different keys are distinct logical scopes.
        assertThat(octopusEnergy.account("A-123", "key-x"))
                .isNotSameAs(octopusEnergy.account("A-123", "key-y"));
    }

    @Test
    void account_closeEvictsFromCache_subsequentCallReturnsFreshInstance() {
        OctopusAccountService first = octopusEnergy.account("A-123", "key-x");
        first.close();
        OctopusAccountService second = octopusEnergy.account("A-123", "key-x");

        assertThat(second).isNotSameAs(first);
    }

    @Test
    void account_closeIsIdempotent_secondCloseIsNoOp() {
        OctopusAccountService account = octopusEnergy.account("A-123", "key-x");
        account.close();
        account.close(); // BaseIdempotentCloseable guards against double-close

        // And after both closes, the cache is clean — a fresh call gets a fresh handle.
        assertThat(octopusEnergy.account("A-123", "key-x")).isNotSameAs(account);
    }

    @Test
    void account_blankAccountId_throws() {
        assertThatThrownBy(() -> octopusEnergy.account("", "key-x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountId must be non-blank");
    }

    @Test
    void account_blankApiKey_throws() {
        assertThatThrownBy(() -> octopusEnergy.account("A-123", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apiKey must be non-blank");
    }

    @Test
    void extractMpanAndMeter_emptyProperties_returnsEmptyList() {
        OctopusAccountData data = OctopusAccountData.builder().build();
        assertThat(OctopusEnergyImpl.extractMpanAndMeter(data)).isEmpty();
    }

    @Test
    void extractMpanAndMeter_noMeterPoints_returnsEmptyList() {
        OctopusAccountData data = OctopusAccountData.builder()
                                                    .addProperties(AccountProperty.builder().build())
                                                    .build();
        assertThat(OctopusEnergyImpl.extractMpanAndMeter(data)).isEmpty();
    }

    @Test
    void extractMpanAndMeter_meterPointWithNoMeters_returnsEmptyList() {
        // The Octopus API can return an electricity_meter_point with an empty `meters` array — typically for a new MPAN that's been registered but doesn't
        // yet have a physical meter associated. Should not produce a row.
        ElectricityMeterPoint mp = ElectricityMeterPoint.builder()
                                                        .setMpan("9999999999999")
                                                        .build();
        OctopusAccountData data = OctopusAccountData.builder()
                                                    .addProperties(AccountProperty.builder().addElectricityMeterPoints(mp).build())
                                                    .build();
        assertThat(OctopusEnergyImpl.extractMpanAndMeter(data)).isEmpty();
    }

    @Test
    void extractMpanAndMeter_populated_returnsCartesianFlatten() {
        // Two meter points, the first with two meters, the second with one — exercises the N×M flatten.
        Tariff tariff = Tariff.builder()
                              .setTariffCode("E-1R-AGILE-23-12-06-A")
                              .setValidFrom(Instant.parse("2020-01-01T00:00:00Z"))
                              .setValidTo(Instant.parse("2099-01-01T00:00:00Z"))
                              .build();
        ElectricityMeterPoint mp1 = ElectricityMeterPoint.builder()
                                                         .setMpan("1111111111111")
                                                         .addMeters(ElectricityMeter.builder().setSerialNumber("11AAA11111").build())
                                                         .addMeters(ElectricityMeter.builder().setSerialNumber("11AAA11112").build())
                                                         .addTariffs(tariff)
                                                         .build();
        ElectricityMeterPoint mp2 = ElectricityMeterPoint.builder()
                                                         .setMpan("2222222222222")
                                                         .addMeters(ElectricityMeter.builder().setSerialNumber("22BBB22222").build())
                                                         .build();
        OctopusAccountData data = OctopusAccountData.builder()
                                                    .addProperties(AccountProperty.builder()
                                                                                  .addElectricityMeterPoints(mp1)
                                                                                  .addElectricityMeterPoints(mp2)
                                                                                  .build())
                                                    .build();

        assertThat(OctopusEnergyImpl.extractMpanAndMeter(data)).containsExactly(
                MpanAndMeter.of("1111111111111", "11AAA11111"),
                MpanAndMeter.of("1111111111111", "11AAA11112"),
                MpanAndMeter.of("2222222222222", "22BBB22222"));
    }

    @Test
    void mpanAndMeter_toStringRedactsBothFields() {
        var pair = MpanAndMeter.of("9999999999999", "99XXX99999");
        assertThat(pair.toString())
                .doesNotContain("9999999999999")
                .doesNotContain("99XXX99999");
    }

    @Test
    void mpanAndMeter_equalityAndHashUseRealValues_soUsableAsKey() {
        // The Immutables-generated equals/hashCode use the real field values; the redacted mask only affects toString.
        assertThat(MpanAndMeter.of("9999999999999", "99XXX99999"))
                .isEqualTo(MpanAndMeter.of("9999999999999", "99XXX99999"))
                .isNotEqualTo(MpanAndMeter.of("1111111111111", "99XXX99999"));
        assertThat(List.of(MpanAndMeter.of("9999999999999", "99XXX99999")))
                .containsExactly(MpanAndMeter.of("9999999999999", "99XXX99999"));
    }

    @Test
    void listProducts_singlePage_parsesResultsAndPassesAvailableAtAsIsoInstant() {
        Instant availableAt = Instant.parse("2024-01-15T12:34:56Z");
        stubGet(OctopusEnergyImpl.BASE_URL + "/products/?available_at=2024-01-15T12:34:56Z",
                """
                {
                  "count": 2, "next": null, "previous": null,
                  "results": [
                    {"code": "AGILE-23-12-06", "display_name": "Agile Octopus", "full_name": "Octopus Agile December 2023 v1",
                     "available_from": "2023-12-06T00:00:00Z", "available_to": null,
                     "brand": "OCTOPUS_ENERGY", "links": []},
                    {"code": "GO-VAR-22-10-14", "display_name": "Octopus Go", "full_name": "Octopus Go October 2022 v1",
                     "available_from": "2022-10-14T00:00:00Z", "available_to": null,
                     "brand": "OCTOPUS_ENERGY", "links": []}
                  ]
                }
                """);

        List<Product> products = octopusEnergy.listProducts(availableAt).join();

        assertThat(products).extracting(Product::code).containsExactly("AGILE-23-12-06", "GO-VAR-22-10-14");
    }

    @Test
    void listProducts_multiPage_recursesOnNextUrlAndConcatenatesInOrder() {
        Instant availableAt = Instant.parse("2024-01-15T00:00:00Z");
        String page1Url = OctopusEnergyImpl.BASE_URL + "/products/?available_at=2024-01-15T00:00:00Z";
        String page2Url = "https://api.octopus.energy/v1/products/?page=2&available_at=2024-01-15T00:00:00Z";
        stubGet(page1Url, """
                          {
                            "count": 3, "next": "%s", "previous": null,
                            "results": [
                              {"code": "P1", "display_name": "One", "full_name": "Product One",
                               "available_from": "2020-01-01T00:00:00Z",
                               "available_to": null, "brand": "OCTOPUS_ENERGY", "links": []}
                            ]
                          }
                          """.formatted(page2Url));
        stubGet(page2Url, """
                          {
                            "count": 3, "next": null, "previous": "%s",
                            "results": [
                              {"code": "P2", "display_name": "Two", "full_name": "Product Two",
                               "available_from": "2020-01-01T00:00:00Z",
                               "available_to": null, "brand": "OCTOPUS_ENERGY", "links": []},
                              {"code": "P3", "display_name": "Three", "full_name": "Product Three",
                               "available_from": "2020-01-01T00:00:00Z",
                               "available_to": null, "brand": "OCTOPUS_ENERGY", "links": []}
                            ]
                          }
                          """.formatted(page1Url));

        List<Product> products = octopusEnergy.listProducts(availableAt).join();

        // Server order across page boundaries — page 1 first, then page 2.
        assertThat(products).extracting(Product::code).containsExactly("P1", "P2", "P3");
    }

    @Test
    void getProductDetails_buildsUrlFromCodeAndParsesPayload() {
        stubGet(OctopusEnergyImpl.BASE_URL + "/products/AGILE-23-12-06/",
                """
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
                """);

        ProductDetails details = octopusEnergy.getProductDetails("AGILE-23-12-06").join();

        assertThat(details.code()).isEqualTo("AGILE-23-12-06");
        assertThat(details.singleRegisterElectricityTariffs())
                .hasEntrySatisfying("_A", region ->
                        assertThat(region).hasEntrySatisfying("direct_debit_monthly", v -> {
                            assertThat(v.code()).isEqualTo("E-1R-AGILE-23-12-06-A");
                            assertThat(v.standingChargeIncVat()).isEqualTo(44.625);
                        }));
    }

    @Test
    void getMpanAndMeter_drivenByAccountFutureCompletion_returnsCartesianFlatten() {
        // `account(...)` triggers the cached HTTP fetch immediately; the stub delivers the JSON synchronously so `accountFuture` completes inline.
        stubGet(OctopusEnergyImpl.BASE_URL + "/accounts/A-12345678",
                """
                {
                  "properties": [
                    {
                      "electricity_meter_points": [
                        {
                          "mpan": "1111111111111",
                          "agreements": [],
                          "meters": [
                            {"serial_number": "11AAA11111"},
                            {"serial_number": "11AAA11112"}
                          ]
                        },
                        {
                          "mpan": "2222222222222",
                          "agreements": [],
                          "meters": [{"serial_number": "22BBB22222"}]
                        }
                      ]
                    }
                  ]
                }
                """);

        try (OctopusAccountService account = octopusEnergy.account("A-12345678", "sk_test_key")) {
            List<MpanAndMeter> rows = account.getMpanAndMeter().join();
            assertThat(rows).containsExactly(
                    MpanAndMeter.of("1111111111111", "11AAA11111"),
                    MpanAndMeter.of("1111111111111", "11AAA11112"),
                    MpanAndMeter.of("2222222222222", "22BBB22222"));
        }
    }

    @Test
    void getMpanAndMeter_accountWithNoProperties_returnsEmptyList() {
        stubGet(OctopusEnergyImpl.BASE_URL + "/accounts/A-EMPTY", """
                                                                  {"properties": []}
                                                                  """);

        try (OctopusAccountService account = octopusEnergy.account("A-EMPTY", "sk_test_key")) {
            assertThat(account.getMpanAndMeter().join()).isEmpty();
        }
    }

    @Test
    void getStandingCharges_buildsUrlAndParsesResults() {
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-12-31T23:59:59Z");
        stubGet(OctopusEnergyImpl.BASE_URL + "/products/AGILE-23-12-06/electricity-tariffs/E-1R-AGILE-23-12-06-A/standing-charges/"
                + "?page_size=25000&period_from=" + from + "&period_to=" + to,
                """
                {
                  "count": 1, "next": null, "previous": null,
                  "results": [
                    {"value_exc_vat": 47.62, "value_inc_vat": 50.0,
                     "valid_from": "2024-01-01T00:00:00Z", "valid_to": "2024-12-31T23:59:59Z"}
                  ]
                }
                """);

        try (OctopusRegionService region = octopusEnergy.region('A')) {
            List<StandingCharge> charges = region.getStandingCharges("AGILE-23-12-06", "E-1R-AGILE-23-12-06-A", from, to).join();
            assertThat(charges).singleElement().satisfies(c -> {
                assertThat(c.valueIncVat()).isEqualTo(50.0);
                assertThat(c.valueExcVat()).isEqualTo(47.62);
            });
        }
    }

    @Test
    void getStandingCharges_tariffCodeRegionMismatch_failedFuture() {
        try (OctopusRegionService region = octopusEnergy.region('A')) {
            assertThatThrownBy(() -> region.getStandingCharges("AGILE-23-12-06", "E-1R-AGILE-23-12-06-B",
                                                               Instant.parse("2024-01-01T00:00:00Z"),
                                                               Instant.parse("2024-01-02T00:00:00Z")).join())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is not for region A");
        }
    }

    @Test
    void getConsumption_singlePage_returnsRowsInOrder() {
        Instant from = Instant.parse("2024-01-15T00:00:00Z");
        Instant to = Instant.parse("2024-01-15T01:00:00Z");
        stubGet(OctopusEnergyImpl.BASE_URL + "/electricity-meter-points/9999999999999/meters/99XXX99999/consumption/"
                + "?page_size=25000&period_from=" + from + "&period_to=" + to,
                """
                {
                  "count": 2, "next": null, "previous": null,
                  "results": [
                    {"consumption": 0.345, "interval_start": "2024-01-15T00:00:00Z", "interval_end": "2024-01-15T00:30:00Z"},
                    {"consumption": 0.422, "interval_start": "2024-01-15T00:30:00Z", "interval_end": "2024-01-15T01:00:00Z"}
                  ]
                }
                """);

        try (OctopusAccountService account = octopusEnergy.account("A-12345678", "sk_test_consumption")) {
            List<ConsumptionRow> rows = account.getConsumption("9999999999999", "99XXX99999", from, to).join();
            assertThat(rows).extracting(ConsumptionRow::consumption).containsExactly(0.345, 0.422);
        }
    }

    @Test
    void getConsumption_multiPage_concatenatesInServerOrder() {
        Instant from = Instant.parse("2024-01-15T00:00:00Z");
        Instant to = Instant.parse("2024-01-15T02:00:00Z");
        String page1 = OctopusEnergyImpl.BASE_URL + "/electricity-meter-points/9999999999999/meters/99XXX99999/consumption/"
                       + "?page_size=25000&period_from=" + from + "&period_to=" + to;
        String page2 = "https://api.octopus.energy/v1/.../consumption/?page=2";
        stubGet(page1, """
                       {"count": 4, "next": "%s", "previous": null,
                        "results": [
                          {"consumption": 0.1, "interval_start": "2024-01-15T00:00:00Z", "interval_end": "2024-01-15T00:30:00Z"},
                          {"consumption": 0.2, "interval_start": "2024-01-15T00:30:00Z", "interval_end": "2024-01-15T01:00:00Z"}
                        ]}
                       """.formatted(page2));
        stubGet(page2, """
                       {"count": 4, "next": null, "previous": "%s",
                        "results": [
                          {"consumption": 0.3, "interval_start": "2024-01-15T01:00:00Z", "interval_end": "2024-01-15T01:30:00Z"},
                          {"consumption": 0.4, "interval_start": "2024-01-15T01:30:00Z", "interval_end": "2024-01-15T02:00:00Z"}
                        ]}
                       """.formatted(page1));

        try (OctopusAccountService account = octopusEnergy.account("A-12345678", "sk_test_consumption")) {
            List<ConsumptionRow> rows = account.getConsumption("9999999999999", "99XXX99999", from, to).join();
            assertThat(rows).extracting(ConsumptionRow::consumption).containsExactly(0.1, 0.2, 0.3, 0.4);
        }
    }

    @Test
    void getConsumption_attachesBasicAuthorizationHeader() {
        // The consumption endpoint is account-scoped — every page request must carry the Basic-auth header derived from the api key.
        Instant from = Instant.parse("2024-01-15T00:00:00Z");
        Instant to = Instant.parse("2024-01-15T00:30:00Z");
        String url = OctopusEnergyImpl.BASE_URL + "/electricity-meter-points/9999999999999/meters/99XXX99999/consumption/"
                     + "?page_size=25000&period_from=" + from + "&period_to=" + to;
        stubGet(url, """
                     {"count": 0, "next": null, "previous": null, "results": []}
                     """);

        try (OctopusAccountService account = octopusEnergy.account("A-12345678", "sk_test_authcheck")) {
            account.getConsumption("9999999999999", "99XXX99999", from, to).join();
        }

        // Find the consumption request among everything sent — there's also the eager /accounts/{id} fetch that fires from the AccountServiceImpl ctor.
        Request consumptionRequest = requestLog.stream()
                                               .filter(r -> r.url().toString().startsWith(OctopusEnergyImpl.BASE_URL + "/electricity-meter-points/"))
                                               .findFirst()
                                               .orElseThrow();
        assertThat(consumptionRequest.header("Authorization")).startsWith("Basic ");
        // The max page size is requested so a long range comes back in as few round-trips as possible (a year of half-hourly data in one page, not ~176).
        assertThat(consumptionRequest.url().toString()).contains("page_size=25000");
    }

    @Test
    void getConsumption_blankMpan_throws() {
        try (OctopusAccountService account = octopusEnergy.account("A-12345678", "sk_test_key")) {
            assertThatThrownBy(() -> account.getConsumption("", "99XXX99999",
                                                            Instant.parse("2024-01-15T00:00:00Z"),
                                                            Instant.parse("2024-01-15T01:00:00Z")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mpan must be non-blank");
        }
    }

    @Test
    void getConsumption_blankMeterSerial_throws() {
        try (OctopusAccountService account = octopusEnergy.account("A-12345678", "sk_test_key")) {
            assertThatThrownBy(() -> account.getConsumption("9999999999999", "",
                                                            Instant.parse("2024-01-15T00:00:00Z"),
                                                            Instant.parse("2024-01-15T01:00:00Z")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("meterSerial must be non-blank");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void accountFetch_4xxAuth_transitionsAuthStateToPermanentFailure(int status) {
        stubError(OctopusEnergyImpl.BASE_URL + "/accounts/A-12345678", status, "{\"detail\":\"unauthorised\"}");
        List<AuthState> observed = new ArrayList<>();

        try (OctopusAccountService account = octopusEnergy.account("A-12345678", "sk_bad")) {
            account.subscribeToAuthState(observed::add);
        }

        // ObservableValue replays the latest value on subscribe; the ctor's account fetch has already completed-and-failed by the time we got here.
        assertThat(observed).last(InstanceOfAssertFactories.type(AuthState.PermanentFailure.class))
                            .satisfies(failure -> assertThat(failure.description()).contains("Response code " + status));
    }

    @Test
    void accountFetch_5xx_leavesAuthStateAsInitialisingTransientFailure() {
        // Server errors and network problems aren't proof that the api key is bad — the bound auth state should stay transient so a retry can recover.
        stubError(OctopusEnergyImpl.BASE_URL + "/accounts/A-12345678", 503, "{\"detail\":\"service unavailable\"}");
        List<AuthState> observed = new ArrayList<>();

        try (OctopusAccountService account = octopusEnergy.account("A-12345678", "sk_maybe_ok")) {
            account.subscribeToAuthState(observed::add);
        }

        assertThat(observed).last().isInstanceOf(AuthState.TransientFailure.class);
    }

    @Test
    void accountFetch_success_transitionsAuthStateToSuccess() {
        stubGet(OctopusEnergyImpl.BASE_URL + "/accounts/A-12345678", """
                                                                     {"properties": []}
                                                                     """);
        List<AuthState> observed = new ArrayList<>();

        try (OctopusAccountService account = octopusEnergy.account("A-12345678", "sk_good")) {
            account.subscribeToAuthState(observed::add);
        }

        assertThat(observed).last().isInstanceOf(AuthState.Success.class);
    }

    @Test
    void authState_consecutiveSameTypeOutcomes_areDeduped_subscriberOnlySeesTypeTransitions() {
        // Account fetch succeeds → Success. Then two more successful authed calls (consumption) → no extra Success notifications. Then 401 → PermanentFailure
        // notification. Then another 401 → no extra PermanentFailure notification.
        stubGet(OctopusEnergyImpl.BASE_URL + "/accounts/A-12345678", """
                                                                     {"properties": []}
                                                                     """);
        Instant from = Instant.parse("2024-01-15T00:00:00Z");
        Instant to = Instant.parse("2024-01-15T01:00:00Z");
        String okUrl = OctopusEnergyImpl.BASE_URL + "/electricity-meter-points/9999999999999/meters/99XXX99999/consumption/"
                       + "?page_size=25000&period_from=" + from + "&period_to=" + to;
        stubGet(okUrl, """
                       {"count": 0, "next": null, "previous": null, "results": []}
                       """);

        List<AuthState> observed = new ArrayList<>();
        try (OctopusAccountService account = octopusEnergy.account("A-12345678", "sk_dedupe")) {
            // Subscribing before any state transitions so we see them all in order (modulo dedupe).
            // Note: the ctor's account fetch has already fired by the time we subscribe — but with the lenient stub it completes synchronously inside the
            // `account(...)` call, so by this line authState is already Success and ObservableValue replays it on subscribe → first observed value = Success.
            account.subscribeToAuthState(observed::add);

            // Two more successful authed calls; if dedupe works, no extra Success notifications.
            account.getConsumption("9999999999999", "99XXX99999", from, to).join();
            account.getConsumption("9999999999999", "99XXX99999", from, to).join();
            assertThat(observed).hasSize(1).first().isInstanceOf(AuthState.Success.class);

            // First 401 → PermanentFailure notification.
            stubError(okUrl, 401, "{\"detail\":\"revoked\"}");
            assertThatThrownBy(() -> account.getConsumption("9999999999999", "99XXX99999", from, to).join()).isNotNull();
            assertThat(observed).hasSize(2);
            assertThat(observed.get(1)).isInstanceOf(AuthState.PermanentFailure.class);

            // Second 401 → no extra PermanentFailure notification (same type, deduped).
            assertThatThrownBy(() -> account.getConsumption("9999999999999", "99XXX99999", from, to).join()).isNotNull();
            assertThat(observed).hasSize(2);
        }
    }

    @Test
    void getConsumption_401_transitionsAuthStateToPermanentFailure() {
        // The account fetch succeeds (auth was good at startup), but the key is later revoked — first authenticated call after revocation fails 401 and
        // must flip the auth state. This pins down that the transition isn't only at the ctor.
        stubGet(OctopusEnergyImpl.BASE_URL + "/accounts/A-12345678", """
                                                                     {"properties": []}
                                                                     """);
        Instant from = Instant.parse("2024-01-15T00:00:00Z");
        Instant to = Instant.parse("2024-01-15T01:00:00Z");
        stubError(OctopusEnergyImpl.BASE_URL + "/electricity-meter-points/9999999999999/meters/99XXX99999/consumption/"
                  + "?page_size=25000&period_from=" + from + "&period_to=" + to,
                  401, "{\"detail\":\"revoked\"}");

        List<AuthState> observed = new ArrayList<>();
        try (OctopusAccountService account = octopusEnergy.account("A-12345678", "sk_revoked")) {
            account.subscribeToAuthState(observed::add);
            assertThatThrownBy(() -> account.getConsumption("9999999999999", "99XXX99999", from, to).join())
                    .hasCauseInstanceOf(HttpResponseException.class);
        }

        // We see Success first (from the account fetch), then PermanentFailure (from the revoked consumption call).
        assertThat(observed).last().isInstanceOf(AuthState.PermanentFailure.class);
    }
}
