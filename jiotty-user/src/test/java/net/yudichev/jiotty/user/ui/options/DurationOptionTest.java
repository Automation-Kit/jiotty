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
    // The last names a length past what a Duration holds: it parses, then overflows.
    @ValueSource(strings = {"two hours", "1x 30y", "PTnonsense", "999999999999999999d"})
    void namesTheCaseRatherThanRepeatingTheParser(String input) {
        CompletableFuture<FormSubmitResult> result = option.onFormSubmit(Optional.of(input));
        clock.tick();

        assertThat(result).succeedsWithin(Duration.ZERO).isEqualTo(FormSubmitResult.rejected(OptionRejectionReasons.INVALID_DURATION));
    }

    /// The parser quotes the text it was handed, and that text is whatever the user typed, so none of it survives into the answer.
    @Test
    void rejectionDoesNotRepeatWhatWasTyped() {
        CompletableFuture<FormSubmitResult> result = option.onFormSubmit(Optional.of("two hours"));
        clock.tick();

        assertThat(result).succeedsWithin(Duration.ZERO).asString().doesNotContain("two hours");
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
