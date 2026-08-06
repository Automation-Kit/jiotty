package net.yudichev.jiotty.connector.octopusenergy.priceforecast;

import com.google.common.base.VerifyException;
import com.google.common.reflect.TypeToken;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static net.yudichev.jiotty.common.rest.HttpStatuses.SERVICE_UNAVAILABLE_503;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/// Also the sibling test of [HttpPriceForecastSource]: every source under test is one, so the canned calls drive its URL formatting, parsing, extraction and
/// failure propagation.
// LENIENT: Call#request() backs a debug-level log argument, so whether it is read depends on the test log level
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class PriceForecastSourcesProviderTest {

    private final List<Request> requests = new ArrayList<>();

    @Mock
    private Call call;

    @Test
    void sourcesAreInFailoverOrderWithStableNames() {
        // the names are the metric `source` tag vocabulary rendered on the resources dashboard, so a rename silently orphans the panel
        assertThat(PriceForecastSourcesProvider.createSources(_ -> call))
                .extracting(PriceForecastSource::name)
                .containsExactly("agilepredict", "x2r", "agileforecast");
    }

    @ParameterizedTest
    @MethodSource
    void parsesRecordedPayload(String sourceName, String resourceName, String expectedUrl, int expectedSlotCount, Instant firstSlotStart, double firstPrice) {
        PriceForecastSource source = cannedSource(sourceName, resourceBytes(resourceName), OK_200);

        List<ForecastPrice> prices = source.getPrices("C", 13).getNow(null);

        assertThat(requests).singleElement().satisfies(request -> assertThat(request.url().toString()).isEqualTo(expectedUrl));
        assertThat(prices).hasSize(expectedSlotCount);
        assertThat(prices.getFirst()).isEqualTo(ForecastPrice.builder()
                                                             .setDateTime(firstSlotStart)
                                                             .setPredictedPrice(firstPrice)
                                                             .build());
    }

    static Stream<Arguments> parsesRecordedPayload() {
        return Stream.of(
                arguments("agilepredict",
                          "agilepredict-sample.json",
                          "https://agilepredict.com/api/C/?days=13&high_low=false",
                          49,
                          Instant.parse("2026-08-01T02:30:00Z"),
                          23.51),
                arguments("x2r",
                          "x2r-sample.json",
                          "https://api.x2r.uk/agile/C",
                          6,
                          Instant.parse("2026-08-01T06:00:00Z"),
                          18.966),
                arguments("agileforecast",
                          "agileforecast-sample.json",
                          "https://agileforecast.co.uk/api/C/?days=13&high_low=false",
                          48,
                          Instant.parse("2026-08-01T09:30:00Z"),
                          9.2));
    }

    @Test
    void serverError_failsTheFetch() {
        PriceForecastSource source = cannedSource("agilepredict", new byte[0], SERVICE_UNAVAILABLE_503);

        assertThat(source.getPrices("C", 13)).failsWithin(Duration.ZERO);
    }

    @Test
    void invalidRegion_rejected() {
        PriceForecastSource source = cannedSource("agilepredict", new byte[0], OK_200);

        assertThatThrownBy(() -> source.getPrices("CC", 13)).isInstanceOf(VerifyException.class);
    }

    @Test
    void invalidDayCount_rejected() {
        PriceForecastSource source = cannedSource("agilepredict", new byte[0], OK_200);

        assertThatThrownBy(() -> source.getPrices("C", 0)).isInstanceOf(VerifyException.class);
    }

    @Test
    void emptyEnvelope_failsTheFetchWithDiagnosis() {
        PriceForecastSource source = cannedSource("agilepredict", "[]".getBytes(UTF_8), OK_200);

        assertThat(source.getPrices("C", 13)).failsWithin(Duration.ZERO)
                                             .withThrowableThat()
                                             .withMessageContaining("response contains no forecasts");
    }

    @Test
    void blankSourceName_rejected() {
        assertThatThrownBy(() -> new HttpPriceForecastSource<>(" ", _ -> call, "url", new TypeToken<List<ForecastPrice>>() {}, prices -> prices))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /// The named source wired to a [Call] mock answering every request with the given canned response.
    private PriceForecastSource cannedSource(String sourceName, byte[] responseBody, int responseCode) {
        doAnswer(invocation -> {
            Callback callback = invocation.getArgument(0);
            asUnchecked(() -> callback.onResponse(call, new Response.Builder()
                    .request(requests.getLast())
                    .protocol(Protocol.HTTP_1_1)
                    .code(responseCode)
                    .message("canned")
                    .body(ResponseBody.create(responseBody, MediaType.get("application/json")))
                    .build()));
            return null;
        }).when(call).enqueue(any());
        return PriceForecastSourcesProvider.createSources(request -> {
                                               requests.add(request);
                                               when(call.request()).thenReturn(request);
                                               return call;
                                           })
                                           .stream()
                                           .filter(source -> source.name().equals(sourceName))
                                           .findFirst()
                                           .orElseThrow();
    }

    private byte[] resourceBytes(String resourceName) {
        return getAsUnchecked(() -> checkNotNull(getClass().getResourceAsStream(resourceName), resourceName).readAllBytes());
    }
}
