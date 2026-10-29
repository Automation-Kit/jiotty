package net.yudichev.jiotty.connector.world.weather;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.backoff.RecordingRetryableOperationExecutor;
import net.yudichev.jiotty.common.geo.LatLon;
import net.yudichev.jiotty.common.misc.RecordingUpstreamHealthHandler;
import net.yudichev.jiotty.common.misc.ThrowingUpstreamHealthHandler;
import net.yudichev.jiotty.common.misc.UpstreamHealthHandler;
import net.yudichev.jiotty.common.rest.OkHttpStubs.PendingCall;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_REQUEST_400;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static net.yudichev.jiotty.common.rest.HttpStatuses.SERVICE_UNAVAILABLE_503;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.response;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.stubCalls;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.stubDeferredCalls;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.takeOnlyPendingCall;
import static net.yudichev.jiotty.common.rest.RestClients.WITHHELD_BODY;
import static net.yudichev.jiotty.connector.world.weather.WeatherServiceImpl.DAYS_PARAMETER;
import static net.yudichev.jiotty.connector.world.weather.WeatherServiceImpl.KEY_PARAMETER;
import static net.yudichev.jiotty.connector.world.weather.WeatherServiceImpl.LOCATION_PARAMETER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class WeatherServiceImplTest {

    private static final LatLon COORDINATES = new LatLon(51.5, -0.1);
    private static final LatLon PRECISE_COORDINATES = new LatLon(51.50735, -0.12776);
    private static final String API_KEY = "test-api-key";
    private static final Instant FORECAST_UNTIL = Instant.parse("2026-06-01T12:00:00Z");

    private final RecordingUpstreamHealthHandler healthHandler = new RecordingUpstreamHealthHandler();
    private final RecordingRetryableOperationExecutor retryExecutor = new RecordingRetryableOperationExecutor();
    private final ProgrammableClock clock = new ProgrammableClock();
    @Mock
    private OkHttpClient httpClient;
    /// Status and body every stubbed call responds with; a `null` status leaves calls pending, for tests that only care about the request being made.
    private @Nullable Integer stubbedStatus;
    private String stubbedBody = "{\"error\": \"stubbed\"}";
    private @Nullable WeatherServiceImpl weatherService;
    private List<Request> issuedRequests;

    @BeforeEach
    void setUp() {
        clock.setTime(Instant.parse("2026-06-01T00:00:00Z"));
        issuedRequests = stubCalls(httpClient, request -> stubbedStatus == null ? null : response(request, stubbedStatus, stubbedBody));
    }

    @AfterEach
    void tearDown() {
        if (weatherService != null) {
            weatherService.stop();
        }
    }

    @Test
    void everyCallPath_routesThroughTheRetryExecutor() {
        // The retry executor sits between every raw call and the health handler, so only a sustained outage reaches it. This pins down that no
        // call path bypasses it.
        WeatherServiceImpl service = createService(healthHandler);

        service.getCurrentWeather(COORDINATES);
        service.getForecastWeather(COORDINATES, FORECAST_UNTIL);

        assertThat(retryExecutor.operationNames()).describedAs("current weather and the forecast — two retried operations").hasSize(2);
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "51.50735; -0.12776; 51.51,-0.13",
            // rounds up across a whole degree, and from below to positive zero
            "52.996; -0.004; 53.0,0.0",
    })
    void everyCallPath_sendsTheCoordinatesRoundedToTwoDecimalPlaces(double lat, double lon, String expectedQuery) {
        WeatherServiceImpl service = createService(healthHandler);

        service.getCurrentWeather(new LatLon(lat, lon));
        service.getForecastWeather(new LatLon(lat, lon), FORECAST_UNTIL);

        assertThat(issuedRequests).extracting(request -> request.url().queryParameter(LOCATION_PARAMETER)).containsExactly(expectedQuery, expectedQuery);
    }

    @Test
    void everyCallPath_sendsOnlyTheKeyTheCoordinatesAndTheForecastDays() {
        WeatherServiceImpl service = createService(healthHandler);

        service.getCurrentWeather(PRECISE_COORDINATES);
        service.getForecastWeather(PRECISE_COORDINATES, FORECAST_UNTIL);

        assertThat(issuedRequests).extracting(request -> request.url().queryParameterNames())
                                  .containsExactly(Set.of(KEY_PARAMETER, LOCATION_PARAMETER), Set.of(KEY_PARAMETER, LOCATION_PARAMETER, DAYS_PARAMETER));
    }

    @ParameterizedTest
    @CsvSource({
            "NaN, -0.12776",
            "51.50735, Infinity",
    })
    void nonFiniteCoordinate_isRejectedBeforeAnythingIsSent(double lat, double lon) {
        WeatherServiceImpl service = createService(healthHandler);

        assertThatThrownBy(() -> service.getCurrentWeather(new LatLon(lat, lon))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getForecastWeather(new LatLon(lat, lon), FORECAST_UNTIL)).isInstanceOf(IllegalArgumentException.class);
        assertThat(issuedRequests).isEmpty();
    }

    @Test
    void transportFailure_reportsTheEndpointWithoutTheApiKeyOrTheCoordinates() {
        // The failure reaches operator alerts, so it names the endpoint and nothing from the query.
        List<PendingCall> pendingCalls = stubDeferredCalls(httpClient);
        WeatherServiceImpl service = createService(healthHandler);

        service.getCurrentWeather(PRECISE_COORDINATES);
        PendingCall pendingCall = takeOnlyPendingCall(pendingCalls);
        String endpoint = pendingCall.request().url().newBuilder().query(null).build().toString();
        pendingCall.fail(new IOException("connection reset"));

        assertThat(healthHandler.failures()).singleElement().satisfies(failure -> assertThat(failure)
                .contains(endpoint)
                .doesNotContain(API_KEY)
                .doesNotContain("51.5")
                .doesNotContain("0.12"));
    }

    @Test
    void serverError_reportsUpstreamFailureWithoutTheResponseBody() {
        stubbedStatus = SERVICE_UNAVAILABLE_503;
        WeatherServiceImpl service = createService(healthHandler);

        service.getCurrentWeather(COORDINATES);

        assertThat(healthHandler.failures()).singleElement().satisfies(f -> assertThat(f)
                .contains("Response code 503")
                .contains(WITHHELD_BODY)
                .doesNotContain(stubbedBody));
        assertThat(healthHandler.successCount()).isZero();
    }

    @Test
    void clientError_reportsNothing() {
        // The API answered — a 400 is a verdict on this request, not an outage every caller shares.
        stubbedStatus = BAD_REQUEST_400;
        WeatherServiceImpl service = createService(healthHandler);

        service.getCurrentWeather(COORDINATES);

        assertThat(healthHandler.failures()).isEmpty();
        assertThat(healthHandler.successCount()).isZero();
    }

    @Test
    void throwingHealthHandler_doesNotFailASuccessfulCall() {
        // A health-handler fault must be contained, never delivered to the caller as the call's outcome.
        stubbedStatus = OK_200;
        stubbedBody = "{\"current\": {\"temp_c\": 15.0}}";
        WeatherServiceImpl service = createService(new ThrowingUpstreamHealthHandler());

        assertThat(service.getCurrentWeather(COORDINATES).join().tempCelsius()).isEqualTo(15.0);
    }

    private WeatherServiceImpl createService(UpstreamHealthHandler handler) {
        weatherService = new WeatherServiceImpl(clock, API_KEY, handler, retryExecutor) {
            @Override
            OkHttpClient createHttpClient() {
                return httpClient;
            }
        };
        weatherService.start();
        return weatherService;
    }
}
