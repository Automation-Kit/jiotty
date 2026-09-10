package net.yudichev.jiotty.user.ui.options;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Pins the two routes past [BaseOption#validate(Object)]: a form submission answers with the refusal, and a caller that set the value itself is thrown it.
class BaseOptionTest {
    private static final OptionRejection REFUSED = OptionRejection.mustBeAtLeast(3);

    private ProgrammableClock clock;
    private TestOption option;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        SchedulingExecutor executor = clock.createSingleThreadedSchedulingExecutor("base-option-test");
        option = new TestOption(executor, OptionMeta.<String>builder()
                                                    .setTabName("Misc")
                                                    .setKey("test.base")
                                                    .setLabel("Test")
                                                    .build());
        option.setValueSync("accepted-before");
    }

    @Test
    void submitStoresAnAcceptedValueAndAnswersWithTheRenderedForm() {
        CompletableFuture<FormSubmitResult> result = option.submit("ok", saved -> saved + "!");
        clock.tick();

        assertThat(result).succeedsWithin(Duration.ZERO).isEqualTo(FormSubmitResult.accepted("ok!"));
        assertThat(option.getValue()).contains("ok");
    }

    @Test
    void submitAnswersARefusalAndLeavesTheOptionHoldingWhatItHeld() {
        option.refuse = true;

        CompletableFuture<FormSubmitResult> result = option.submit("rejected", saved -> saved);
        clock.tick();

        assertThat(result).succeedsWithin(Duration.ZERO).isEqualTo(FormSubmitResult.rejected(REFUSED));
        assertThat(option.getValue()).contains("accepted-before");
        assertThat(option.changes).as("a refused value never reaches onChanged").containsExactly("accepted-before");
    }

    /// The other route out of the same rule: the caller chose the value, so there is nobody to hand a refusal back to.
    @Test
    void setValueSyncThrowsTheSameRefusal() {
        option.refuse = true;

        assertThatThrownBy(() -> option.setValueSync("rejected"))
                .isInstanceOfSatisfying(OptionValueRejectedException.class,
                                        thrown -> assertThat(thrown.reason()).isEqualTo(REFUSED.reason()));
        assertThat(option.getValue()).contains("accepted-before");
    }

    /// The value is produced inside the task that stores it, so an option deriving its next value from the current one reads and writes in the same turn.
    @Test
    void submitBuildsTheValueOnTheExecutor() {
        CompletableFuture<FormSubmitResult> result = option.submit(() -> option.requireValue() + "-appended", saved -> saved);

        assertThat(option.getValue()).as("nothing runs before the executor does").contains("accepted-before");
        clock.tick();

        assertThat(result).succeedsWithin(Duration.ZERO).isEqualTo(FormSubmitResult.accepted("accepted-before-appended"));
    }

    @Test
    void submitJsonStoresABodyThatIsAlreadyTheValue() {
        CompletableFuture<FormSubmitResult> result = option.submitJson(Optional.of("\"from-json\""), String.class);
        clock.tick();

        assertThat(result).succeedsWithin(Duration.ZERO).isEqualTo(FormSubmitResult.accepted("from-json"));
        assertThat(option.getValue()).contains("from-json");
    }

    @Test
    void submitJsonStoresWhatTheMapperMakesOfTheBody() {
        CompletableFuture<FormSubmitResult> result = option.submitJson(Optional.of("42"), Integer.class, number -> "n=" + number);
        clock.tick();

        assertThat(result).succeedsWithin(Duration.ZERO).isEqualTo(FormSubmitResult.accepted("n=42"));
    }

    /// A client sending the wrong shape is not a fault of ours, so both shapes of unreadable body are refused rather than failed — and the answer repeats
    /// nothing of what was sent.
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"not json at all", "null", "{\"unexpected\": true}"})
    void submitJsonRefusesABodyItCannotRead(@Nullable String body) {
        CompletableFuture<FormSubmitResult> result = option.submitJson(Optional.ofNullable(body), Integer.class, number -> "n=" + number);
        clock.tick();

        assertThat(result).succeedsWithin(Duration.ZERO).isEqualTo(FormSubmitResult.rejected(OptionRejectionReasons.INVALID_VALUE));
        assertThat(option.getValue()).contains("accepted-before");
    }

    /// Re-submitting what the option already holds is accepted without re-running [BaseOption#onChanged()] — the no-op that lets an option answer with its
    /// current value by submitting it.
    @Test
    void submittingTheStoredValueAgainChangesNothing() {
        CompletableFuture<FormSubmitResult> result = option.submit("accepted-before", saved -> saved);
        clock.tick();

        assertThat(result).succeedsWithin(Duration.ZERO).isEqualTo(FormSubmitResult.accepted("accepted-before"));
        assertThat(option.changes).as("onChanged runs once, for the setUp value").containsExactly("accepted-before");
    }

    /// [BaseOption#onChanged()] enriches the value it was handed, so a failure there leaves the option holding what it held.
    @Test
    void aFailureEnrichingTheValuePutsThePreviousOneBack() {
        option.failOnChange = true;

        assertThatThrownBy(() -> option.setValueSync("doomed")).isInstanceOf(IllegalStateException.class);
        assertThat(option.getValue()).contains("accepted-before");
    }

    @Test
    void formatsAsItsKeyAndValue() {
        assertThat(option).hasToString("test.base=accepted-before");
    }

    private static final class TestOption extends BaseOption<String> {
        private final List<String> changes = new ArrayList<>();
        private boolean refuse;
        private boolean failOnChange;

        TestOption(SchedulingExecutor executor, OptionMeta<String> meta) {
            super(executor, meta);
        }

        @Override
        protected Optional<OptionRejection> validate(@Nullable String value) {
            return refuse ? Optional.of(REFUSED) : Optional.empty();
        }

        @Override
        public @Nullable String onChanged() {
            if (failOnChange) {
                throw new IllegalStateException("enriching failed");
            }
            changes.add(value());
            return value();
        }

        @Override
        public CompletableFuture<FormSubmitResult> onFormSubmit(Optional<String> value) {
            return submit(value.orElse(null));
        }

        @Override
        public OptionDto toDtoUnsafe() {
            throw new UnsupportedOperationException("not part of what this test pins");
        }

    }
}
