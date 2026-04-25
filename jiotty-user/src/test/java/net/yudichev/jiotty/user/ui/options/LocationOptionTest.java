package net.yudichev.jiotty.user.ui.options;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.geo.LatLon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

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
        Object result = await(option.onFormSubmit(Optional.of("{\"lat\":51.5,\"lon\":-0.12}")));

        assertThat(result).isEqualTo(new LatLon(51.5, -0.12));
        assertThat(option.getValue()).contains(new LatLon(51.5, -0.12));
    }

    @Test
    void onFormSubmitClearsValueOnEmptyInput() {
        option.setValueSync(new LatLon(10.0, 20.0));

        await(option.onFormSubmit(Optional.of("")));

        assertThat(option.getValue()).isEmpty();
    }

    @Test
    void onFormSubmitClearsValueOnAbsentInput() {
        option.setValueSync(new LatLon(10.0, 20.0));

        await(option.onFormSubmit(Optional.empty()));

        assertThat(option.getValue()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource
    void onFormSubmitFailsOnInvalidInput(String input, String expectedMessageSubstring) {
        CompletableFuture<?> result = option.onFormSubmit(Optional.of(input));
        clock.tick();

        assertThatThrownBy(result::get)
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedMessageSubstring);
    }

    static Stream<Arguments> onFormSubmitFailsOnInvalidInput() {
        return Stream.of(
                // malformed payload
                arguments("not json", "Invalid location JSON"),
                // latitude out of range
                arguments("{\"lat\":-91.0,\"lon\":0.0}", "Latitude out of range"),
                arguments("{\"lat\":90.5,\"lon\":0.0}", "Latitude out of range"),
                // longitude out of range
                arguments("{\"lat\":0.0,\"lon\":-180.5}", "Longitude out of range"),
                arguments("{\"lat\":0.0,\"lon\":180.5}", "Longitude out of range")
        );
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

    private <T> T await(CompletableFuture<T> future) {
        clock.tick();
        try {
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new AssertionError(e);
        }
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
