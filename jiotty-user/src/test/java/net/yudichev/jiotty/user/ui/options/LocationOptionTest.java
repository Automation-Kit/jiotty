package net.yudichev.jiotty.user.ui.options;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.geo.LatLon;
import net.yudichev.jiotty.common.lang.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class LocationOptionTest {
    private static final String OPTION_KEY = "test.location";

    private ProgrammableClock clock;
    private TestLocationOption option;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        SchedulingExecutor executor = clock.createSingleThreadedSchedulingExecutor("location-option-test");
        option = new TestLocationOption(executor, OptionMeta.<LatLon>builder()
                                                            .setTabName("Misc")
                                                            .setKey(OPTION_KEY)
                                                            .setLabel("Home Location")
                                                            .build());
    }

    @Test
    void onFormSubmitParsesValidJson() {
        CompletableFuture<?> result = option.onFormSubmit(Optional.of("{\"lat\":51.5,\"lon\":-0.12}"));
        clock.tick();

        assertThat(result).succeedsWithin(Duration.ZERO).isEqualTo(FormSubmitResult.accepted(new LatLon(51.5, -0.12)));
        assertThat(option.getValue()).contains(new LatLon(51.5, -0.12));
    }

    @Test
    void onFormSubmitClearsValueOnEmptyInput() {
        option.setValueSync(new LatLon(10.0, 20.0));

        CompletableFuture<?> result = option.onFormSubmit(Optional.of(""));
        clock.tick();

        assertThat(result).succeedsWithin(Duration.ZERO);
        assertThat(option.getValue()).isEmpty();
    }

    @Test
    void onFormSubmitClearsValueOnAbsentInput() {
        option.setValueSync(new LatLon(10.0, 20.0));

        CompletableFuture<?> result = option.onFormSubmit(Optional.empty());
        clock.tick();

        assertThat(result).succeedsWithin(Duration.ZERO);
        assertThat(option.getValue()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // malformed payload
            "not json",
            // a payload that parses to nothing at all
            "null",
            // latitude out of range
            "{\"lat\":-91.0,\"lon\":0.0}",
            "{\"lat\":90.5,\"lon\":0.0}",
            // longitude out of range
            "{\"lat\":0.0,\"lon\":-180.5}",
            "{\"lat\":0.0,\"lon\":180.5}",
            // not a number: every comparison against a bound is false, so a check written as two rejecting comparisons would let these through
            "{\"lat\":\"NaN\",\"lon\":0.0}",
            "{\"lat\":0.0,\"lon\":\"NaN\"}",
            "{\"lat\":\"Infinity\",\"lon\":0.0}",
            // a coordinate absent or null: read into primitives these arrive as 0.0, which is in range and would save as a point off the coast of Africa
            "{\"lat\":51.5}",
            "{\"lon\":-0.12}",
            "{\"lat\":51.5,\"lon\":null}",
            "{\"lat\":null,\"lon\":null}",
            "{}"})
    void onFormSubmitRefusesInvalidInput(String input) {
        CompletableFuture<FormSubmitResult> result = option.onFormSubmit(Optional.of(input));
        clock.tick();

        assertThat(result).succeedsWithin(Duration.ZERO).isEqualTo(FormSubmitResult.rejected(OptionRejectionReasons.INVALID_LOCATION));
        assertThat(option.getValue()).as("a refused location leaves the option holding what it held").isEmpty();
    }

    /// A rejection travels to a response body and to the server log, so it must not carry the coordinate that caused it.
    @Test
    void outOfRangeRejectionNamesNoCoordinate() {
        CompletableFuture<FormSubmitResult> result = option.onFormSubmit(Optional.of(Json.stringify(new LatLon(91.5074, -0.1278))));
        clock.tick();

        assertThat(result).succeedsWithin(Duration.ZERO).asString().doesNotContain("91.5074", "-0.1278");
    }

    @Test
    void toDtoUnsafeWithValueExposesLatLon() {
        option.setValueSync(new LatLon(51.5, -0.12));

        OptionDto dto = option.toDtoUnsafe();

        assertThat(dto).isInstanceOf(StandardOptionDtos.Location.class);
        var location = (StandardOptionDtos.Location) dto;
        assertThat(location.type()).isEqualTo("location");
        assertThat(location.key()).isEqualTo(OPTION_KEY);
        assertThat(location.label()).isEqualTo("Home Location");
        assertThat(location.tabName()).isEqualTo("Misc");
        assertThat(location.value()).isEqualTo(new LatLon(51.5, -0.12));
    }

    @Test
    void toDtoUnsafeWithoutValueExposesNull() {
        OptionDto dto = option.toDtoUnsafe();

        assertThat(dto).isInstanceOf(StandardOptionDtos.Location.class);
        var location = (StandardOptionDtos.Location) dto;
        assertThat(location.value()).isNull();
    }

    private static final class TestLocationOption extends LocationOption {
        TestLocationOption(SchedulingExecutor executor, OptionMeta<LatLon> meta) {
            super(executor, meta);
        }

        @Override
        public LatLon onChanged() {
            return value();
        }
    }
}
