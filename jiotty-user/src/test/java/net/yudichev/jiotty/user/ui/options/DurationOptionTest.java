package net.yudichev.jiotty.user.ui.options;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.time.FriendlyDurationFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

class DurationOptionTest {
    private static final String OPTION_KEY = "test.duration";

    private ProgrammableClock clock;
    private TestDurationOption option;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        SchedulingExecutor executor = clock.createSingleThreadedSchedulingExecutor("duration-option-test");
        option = new TestDurationOption(executor, OptionMeta.<Duration>builder()
                                                            .setTabName("Misc")
                                                            .setKey(OPTION_KEY)
                                                            .setLabel("Lead time")
                                                            .build());
    }

    @Test
    void onFormSubmitParsesTheFriendlyForms() {
        CompletableFuture<?> result = option.onFormSubmit(Optional.of("2h 30m"));
        clock.tick();

        assertThat(result).succeedsWithin(Duration.ZERO);
        assertThat(option.getValue()).contains(Duration.ofMinutes(150));
    }

    @Test
    void onFormSubmitClearsValueOnBlankInput() {
        option.setValueSync(Duration.ofHours(1));

        CompletableFuture<?> result = option.onFormSubmit(Optional.of("  "));
        clock.tick();

        assertThat(result).succeedsWithin(Duration.ZERO);
        assertThat(option.getValue()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"two hours", "1x 30y", "PTnonsense"})
    void namesTheCaseRatherThanRepeatingTheParser(String input) {
        CompletableFuture<?> result = option.onFormSubmit(Optional.of(input));
        clock.tick();

        assertThat(result)
                .failsWithin(Duration.ZERO)
                .withThrowableOfType(ExecutionException.class)
                .havingCause()
                .isInstanceOfSatisfying(OptionValueRejectedException.class,
                                        rejection -> assertThat(rejection.reason()).isEqualTo(OptionRejectionReasons.INVALID_DURATION));
    }

    /// The parser quotes the text it was handed, and that text is whatever the user typed, so it stays a cause for the log rather than the reason.
    @Test
    void rejectionDoesNotRepeatWhatWasTyped() {
        CompletableFuture<?> result = option.onFormSubmit(Optional.of("two hours"));
        clock.tick();

        assertThat(result)
                .failsWithin(Duration.ZERO)
                .withThrowableOfType(ExecutionException.class)
                .havingCause()
                .satisfies(rejection -> assertThat(rejection.getMessage()).doesNotContain("two hours"));
    }

    @Test
    void toDtoUnsafeCarriesTheValueInTheFriendlyForm() {
        option.setValueSync(Duration.ofMinutes(150));

        OptionDto dto = option.toDtoUnsafe();

        assertThat(dto).isInstanceOfSatisfying(StandardOptionDtos.Duration.class, duration -> {
            assertThat(duration.key()).isEqualTo(OPTION_KEY);
            assertThat(duration.valueHuman()).isEqualTo(FriendlyDurationFormat.formatHuman(Duration.ofMinutes(150)));
        });
    }

    @Test
    void toDtoUnsafeCarriesNoValueWhenUnset() {
        OptionDto dto = option.toDtoUnsafe();

        assertThat(dto).isInstanceOfSatisfying(StandardOptionDtos.Duration.class, duration -> assertThat(duration.valueHuman()).isNull());
    }

    private static final class TestDurationOption extends DurationOption {
        TestDurationOption(SchedulingExecutor executor, OptionMeta<Duration> meta) {
            super(executor, meta);
        }

        @Override
        public Duration onChanged() {
            return value();
        }
    }
}
