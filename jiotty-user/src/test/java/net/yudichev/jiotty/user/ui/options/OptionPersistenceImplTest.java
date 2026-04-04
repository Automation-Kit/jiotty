package net.yudichev.jiotty.user.ui.options;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.persistence.varstore.InMemoryVarStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OptionPersistenceImplTest {
    private static final String OPTION_KEY = "test.option";
    private static final String STORE_KEY = OptionPersistenceImpl.UI_OPTIONS_KEY_PREFIX + '.' + OPTION_KEY;

    private SchedulingExecutor executor;
    private InMemoryVarStore varStore;
    private OptionPersistenceImpl persistence;
    private TestTextOption option;

    @BeforeEach
    void setUp() {
        var clock = new ProgrammableClock();
        executor = clock.createSingleThreadedSchedulingExecutor("option-persistence-test");
        varStore = new InMemoryVarStore();
        persistence = new OptionPersistenceImpl(varStore);
        option = createOption(null);
    }

    @AfterEach
    void tearDown() {
        Closeable.closeIfNotNull(executor);
    }

    @Test
    void saveStoresPresentOptionValue() {
        option.setValueSync("stored");

        persistence.save(option);

        assertThat(varStore.readValue(String.class, STORE_KEY)).contains("stored");
    }

    @Test
    void saveClearsMissingOptionValue() {
        varStore.saveValue(STORE_KEY, "old");

        persistence.save(option);

        assertThat(varStore.readValue(String.class, STORE_KEY)).isEmpty();
    }

    @Test
    void loadRestoresPersistedValue() {
        varStore.saveValue(STORE_KEY, "stored");

        persistence.load(option);

        assertThat(option.getValue()).contains("stored");
    }

    @Test
    void loadAppliesDefaultWhenStoredValueMissing() {
        TestTextOption defaultedOption = createOption("default");

        persistence.load(defaultedOption);

        assertThat(defaultedOption.getValue()).contains("default");
    }

    private TestTextOption createOption(String defaultValue) {
        return new TestTextOption(executor, new OptionMeta<>("Tab", OPTION_KEY, "Label", defaultValue));
    }

    private static final class TestTextOption extends TextOption {
        TestTextOption(SchedulingExecutor executor, OptionMeta<String> meta) {
            super(executor, meta);
        }

        @Override
        public String onChanged() {
            return value();
        }
    }
}
