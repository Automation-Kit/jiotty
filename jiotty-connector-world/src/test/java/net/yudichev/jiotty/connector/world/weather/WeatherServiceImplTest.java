package net.yudichev.jiotty.connector.world.weather;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.backoff.RecordingRetryableOperationExecutor;
import net.yudichev.jiotty.common.geo.LatLon;
import net.yudichev.jiotty.common.misc.RecordingUpstreamHealthHandler;
import net.yudichev.jiotty.common.misc.ThrowingUpstreamHealthHandler;
import net.yudichev.jiotty.common.misc.UpstreamHealthHandler;
import okhttp3.OkHttpClient;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static net.yudichev.jiotty.common.rest.OkHttpStubs.response;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.stubCalls;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class WeatherServiceImplTest {

    private static final LatLon COORDINATES = new LatLon(51.5, -0.1);

    private final RecordingUpstreamHealthHandler healthHandler = new RecordingUpstreamHealthHandler();
    private final RecordingRetryableOperationExecutor retryExecutor = new RecordingRetryableOperationExecutor();
    private final ProgrammableClock clock = new ProgrammableClock();
    @Mock
    private OkHttpClient httpClient;
    /// Status and body every stubbed call responds with; a `null` status leaves calls pending, for tests that only care about the request being made.
    private @Nullable Integer stubbedStatus;
    private String stubbedBody = "{\"error\": \"stubbed\"}";
    private @Nullable WeatherServiceImpl weatherService;

    @BeforeEach
    void setUp() {
        clock.setTime(Instant.parse("2026-06-01T00:00:00Z"));
        stubCalls(httpClient, request -> stubbedStatus == null ? null : response(request, stubbedStatus, stubbedBody));
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
        service.getForecastWeather(COORDINATES, Instant.parse("2026-06-01T12:00:00Z"));

        assertThat(retryExecutor.operationNames()).describedAs("current weather and the forecast — two retried operations").hasSize(2);
    }

    @Test
    void serverError_reportsUpstreamFailure() {
        stubbedStatus = 503;
        WeatherServiceImpl service = createService(healthHandler);

        service.getCurrentWeather(COORDINATES);

        assertThat(healthHandler.failures()).singleElement().satisfies(f -> assertThat(f).contains("Response code 503"));
        assertThat(healthHandler.successCount()).isZero();
    }

    @Test
    void clientError_reportsNothing() {
        // The API answered — a 400 is a verdict on this request, not an outage every caller shares.
        stubbedStatus = 400;
        WeatherServiceImpl service = createService(healthHandler);

        service.getCurrentWeather(COORDINATES);

        assertThat(healthHandler.failures()).isEmpty();
        assertThat(healthHandler.successCount()).isZero();
    }

    @Test
    void throwingHealthHandler_doesNotFailASuccessfulCall() {
        // A health-handler fault must be contained, never delivered to the caller as the call's outcome.
        stubbedStatus = 200;
        stubbedBody = "{\"current\": {\"temp_c\": 15.0}}";
        WeatherServiceImpl service = createService(new ThrowingUpstreamHealthHandler());

        assertThat(service.getCurrentWeather(COORDINATES).join().tempCelsius()).isEqualTo(15.0);
    }

    private WeatherServiceImpl createService(UpstreamHealthHandler handler) {
        weatherService = new WeatherServiceImpl(clock, "test-api-key", handler, retryExecutor) {
            @Override
            OkHttpClient createHttpClient() {
                return httpClient;
            }
        };
        weatherService.start();
        return weatherService;
    }
}
