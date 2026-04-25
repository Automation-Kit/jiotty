package net.yudichev.jiotty.user.ui.options;

import com.google.common.collect.ImmutableMap;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class MultiSelectOptionTest {
    private static final String OPTION_KEY = "test.multiselect";
    private static final ImmutableMap<String, String> ALL_OPTIONS = ImmutableMap.of(
            "a", "Alpha",
            "b", "Bravo",
            "c", "Charlie");

    private ProgrammableClock clock;
    private TestMultiSelectOption option;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        SchedulingExecutor executor = clock.createSingleThreadedSchedulingExecutor("multiselect-option-test");
        option = new TestMultiSelectOption(executor,
                                           OptionMeta.<Set<String>>builder()
                                                     .setTabName("Misc")
                                                     .setKey(OPTION_KEY)
                                                     .setLabel("Things")
                                                     .build(),
                                           ALL_OPTIONS);
    }

    @ParameterizedTest
    @MethodSource
    void onFormSubmitStoresParsedSet(Optional<String> input, Set<String> expected) {
        CompletableFuture<?> result = option.onFormSubmit(input);
        clock.tick();

        assertThat(result).succeedsWithin(Duration.ZERO);
        assertThat(option.getValue()).contains(expected);
    }

    static Stream<Arguments> onFormSubmitStoresParsedSet() {
        return Stream.of(
                // absent input clears
                arguments(Optional.empty(), Set.of()),
                // empty string clears too — must NOT split into [""]
                arguments(Optional.of(""), Set.of()),
                // single id
                arguments(Optional.of("a"), Set.of("a")),
                // comma-separated ids
                arguments(Optional.of("a,b,c"), Set.of("a", "b", "c")));
    }

    @Test
    void constructorRejectsAllOptionsKeyWithComma() {
        SchedulingExecutor executor = clock.createSingleThreadedSchedulingExecutor("rejects");
        assertThatThrownBy(() -> new TestMultiSelectOption(executor,
                                                           OptionMeta.<Set<String>>builder()
                                                                     .setTabName("Misc")
                                                                     .setKey(OPTION_KEY)
                                                                     .setLabel("Things")
                                                                     .build(),
                                                           ImmutableMap.of("a,b", "Comma")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("comma");
    }

    @Test
    void toDtoUnsafeWithValueExposesAllOptionsAndSelection() {
        option.setValueSync(Set.of("a", "c"));

        OptionDto dto = option.toDtoUnsafe();

        assertThat(dto).isInstanceOfSatisfying(StandardOptionDtos.MultiSelect.class, multi -> {
            assertThat(multi.type()).isEqualTo("multiselect");
            assertThat(multi.key()).isEqualTo(OPTION_KEY);
            assertThat(multi.label()).isEqualTo("Things");
            assertThat(multi.tabName()).isEqualTo("Misc");
            assertThat(multi.allOptions()).isEqualTo(ALL_OPTIONS);
            assertThat(multi.selectedIds()).containsExactlyInAnyOrder("a", "c");
        });
    }

    @Test
    void toDtoUnsafeWithoutValueExposesEmptySelection() {
        OptionDto dto = option.toDtoUnsafe();

        assertThat(dto).isInstanceOfSatisfying(StandardOptionDtos.MultiSelect.class,
                                               multi -> assertThat(multi.selectedIds()).isEmpty());
    }

    private static final class TestMultiSelectOption extends MultiSelectOption {
        TestMultiSelectOption(SchedulingExecutor executor, OptionMeta<Set<String>> meta, ImmutableMap<String, String> allOptions) {
            super(executor, meta, allOptions);
        }

        @Override
        public Set<String> onChanged() {
            return value();
        }
    }
}
