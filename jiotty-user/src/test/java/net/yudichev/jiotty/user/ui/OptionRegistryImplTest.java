package net.yudichev.jiotty.user.ui;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.testutil.LogCapture;
import net.yudichev.jiotty.user.ui.options.OptionMeta;
import net.yudichev.jiotty.user.ui.options.OptionPersistence;
import net.yudichev.jiotty.user.ui.options.TestTextOption;
import org.apache.logging.log4j.Level;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
class OptionRegistryImplTest {
    private static final String OPTION_KEY = "test.registry";

    /// The value of each option that the registry passes to the [OptionSaveListener], in call order. A test may add its own entries between these values to
    /// check the order of calls.
    private final List<String> savedValues = new ArrayList<>();
    private ProgrammableClock clock;
    private SchedulingExecutor executor;
    @Mock
    private OptionPersistence persistence;
    /// The exception that the [OptionSaveListener] throws after it records a value. `null` when the listener returns normally.
    private @Nullable RuntimeException saveListenerFailure;
    /// `null` until [#setUp] constructs the registry.
    private @Nullable OptionRegistryImpl registry;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        executor = clock.createSingleThreadedSchedulingExecutor("option-registry-test");
        registry = new OptionRegistryImpl(persistence, option -> {
            savedValues.add((String) option.value());
            if (saveListenerFailure != null) {
                throw saveListenerFailure;
            }
        });
        registry.start();
    }

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.stop();
            clock.tick();
        }
    }

    @Test
    void theSaveListenerReceivesAnOptionWhenItIsRegisteredAndAfterEachChange() {
        TestTextOption option = registerOptionHolding("first");

        option.setValue("second");
        clock.tick();

        assertThat(savedValues).containsExactly("first", "second");
    }

    @Test
    void theSaveListenerReceivesAnOptionOnlyOnceItIsSaved() {
        doAnswer(_ -> {
            savedValues.add("persisted");
            return null;
        }).when(persistence).save(any());

        registerOptionHolding("first");

        assertThat(savedValues).containsExactly("persisted", "first");
    }

    /// After a registration is closed, an option registered again under the same key is passed to the [OptionSaveListener] when the option is registered and
    /// after each change.
    @Test
    void theSaveListenerReceivesAnOptionRegisteredAgainUnderTheSameKey() {
        Closeable registration = registry().register(newOptionHolding("first"));
        clock.tick();
        registration.close();
        savedValues.clear();

        TestTextOption replacement = registerOptionHolding("replacement");
        replacement.setValue("changed");
        clock.tick();

        assertThat(savedValues).containsExactly("replacement", "changed");
    }

    @ParameterizedTest
    @MethodSource
    void aSaveListenerThatThrowsStillLeavesTheSnapshotSubscribersNotified(RuntimeException failure, LogCapture.CapturedLine expectedLogLine) {
        var snapshotChanges = new ArrayList<String>();
        registry().subscribeToSnapshotChanges(() -> snapshotChanges.add("changed"));
        TestTextOption option = registerOptionHolding("first");
        snapshotChanges.clear();
        saveListenerFailure = failure;

        try (LogCapture log = LogCapture.capture(OptionRegistryImpl.class, Level.DEBUG)) {
            option.setValue("second");
            clock.tick();

            assertThat(log.lines()).containsExactly(expectedLogLine);
        }
        assertThat(savedValues).containsExactly("first", "second");
        assertThat(snapshotChanges).containsExactly("changed");
        assertThat(option.getValue()).contains("second");
    }

    static Stream<Arguments> aSaveListenerThatThrowsStillLeavesTheSnapshotSubscribersNotified() {
        var failure = new IllegalStateException("save listener boom");
        var rejection = new RejectedExecutionException("save listener queue full");
        return Stream.of(arguments(failure,
                                   new LogCapture.CapturedLine(Level.INFO, "The save listener failed on option " + OPTION_KEY, List.of(failure.toString()))),
                         arguments(rejection,
                                   new LogCapture.CapturedLine(Level.INFO, "The save listener failed on option " + OPTION_KEY, List.of(rejection.toString()))));
    }

    private TestTextOption registerOptionHolding(String value) {
        TestTextOption option = newOptionHolding(value);
        registry().register(option);
        clock.tick();
        return option;
    }

    private TestTextOption newOptionHolding(String value) {
        var option = new TestTextOption(executor, OptionMeta.<String>builder().setTabName("Misc").setKey(OPTION_KEY).setLabel("Test").build());
        option.setValueSync(value);
        return option;
    }

    private OptionRegistryImpl registry() {
        assert registry != null : "the registry is started";
        return registry;
    }
}
