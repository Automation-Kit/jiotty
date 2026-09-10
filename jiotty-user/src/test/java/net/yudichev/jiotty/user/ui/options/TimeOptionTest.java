package net.yudichev.jiotty.user.ui.options;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class TimeOptionTest {
    private static final String OPTION_KEY = "test.time";

    private ProgrammableClock clock;
    private TestTimeOption option;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        SchedulingExecutor executor = clock.createSingleThreadedSchedulingExecutor("time-option-test");
        option = new TestTimeOption(executor, OptionMeta.<LocalTime>builder()
                                                        .setTabName("Misc")
                                                        .setKey(OPTION_KEY)
                                                        .setLabel("Wake time")
                                                        .build());
    }

    @Test
    void onFormSubmitParsesATimeOfDay() {
        CompletableFuture<?> result = option.onFormSubmit(Optional.of("07:30"));
        clock.tick();

        assertThat(result).succeedsWithin(Duration.ZERO).isEqualTo(FormSubmitResult.accepted("07:30"));
        assertThat(option.getValue()).contains(LocalTime.of(7, 30));
    }

    @Test
    void onFormSubmitClearsValueOnAbsentInput() {
        option.setValueSync(LocalTime.of(7, 30));

        CompletableFuture<?> result = option.onFormSubmit(Optional.empty());
        clock.tick();

        assertThat(result).succeedsWithin(Duration.ZERO);
        assertThat(option.getValue()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"25:00", "half past seven", "07:30:99", ""})
    void namesTheCaseRatherThanRepeatingTheParser(String input) {
        CompletableFuture<FormSubmitResult> result = option.onFormSubmit(Optional.of(input));
        clock.tick();

        assertThat(result).succeedsWithin(Duration.ZERO).isEqualTo(FormSubmitResult.rejected(OptionRejectionReasons.INVALID_TIME));
    }

    /// The parser quotes the text it was handed, and that text is whatever the user typed, so none of it survives into the answer.
    @Test
    void rejectionDoesNotRepeatWhatWasTyped() {
        CompletableFuture<FormSubmitResult> result = option.onFormSubmit(Optional.of("half past seven"));
        clock.tick();

        assertThat(result).succeedsWithin(Duration.ZERO).asString().doesNotContain("half past seven");
    }

    @Test
    void toDtoUnsafeCarriesTheValueAsAnIsoTime() {
        option.setValueSync(LocalTime.of(7, 30));

        OptionDto dto = option.toDtoUnsafe();

        assertThat(dto).isInstanceOfSatisfying(StandardOptionDtos.Time.class, time -> {
            assertThat(time.key()).isEqualTo(OPTION_KEY);
            assertThat(time.value()).isEqualTo("07:30");
        });
    }

    @Test
    void toDtoUnsafeCarriesNoValueWhenUnset() {
        OptionDto dto = option.toDtoUnsafe();

        assertThat(dto).isInstanceOfSatisfying(StandardOptionDtos.Time.class, time -> assertThat(time.value()).isNull());
    }

    private static final class TestTimeOption extends TimeOption {
        TestTimeOption(SchedulingExecutor executor, OptionMeta<LocalTime> meta) {
            super(executor, meta);
        }

        @Override
        public LocalTime onChanged() {
            return value();
        }
    }
}
