package net.yudichev.jiotty.persistence.varstore;

import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.StringFormattable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersistedValueTest {
    private static final String KEY = "test.value";

    private InMemoryVarStore varStore;
    private PersistedValue<String> value;

    @BeforeEach
    void setUp() {
        varStore = new InMemoryVarStore();
        value = new PersistedValue<>(varStore, KEY, String.class);
    }

    @Test
    void restoringAnAbsentRow_yieldsNothing() {
        value.restore();

        assertThat(value.get()).isEmpty();
    }

    @Test
    void setValue_isReadBackByAFreshInstanceOverTheSameStore() {
        value.restore();
        value.set("stored");

        var reopenedValue = new PersistedValue<>(varStore, KEY, String.class);
        reopenedValue.restore();

        assertThat(reopenedValue.get()).hasValue("stored");
    }

    @Test
    void clear_removesTheRowRatherThanStoringAnEmptyMarker() {
        value.restore();
        value.set("stored");

        value.clear();

        assertThat(value.get()).isEmpty();
        assertThat(varStore.readValue(String.class, KEY)).isEmpty();
    }

    @Test
    void clear_withNothingStored_leavesTheStoreAlone() {
        value.restore();

        value.clear();

        assertThat(varStore.readValue(String.class, KEY)).isEmpty();
    }

    @Test
    void set_replacesWhatTheRowHeld() {
        value.restore();
        value.set("first");

        value.set("second");

        assertThat(value.get()).hasValue("second");
        assertThat(varStore.readValue(String.class, KEY)).hasValue("second");
    }

    @Test
    void renderingDelegatesToTheHeldValue() {
        var redactingValue = new PersistedValue<>(varStore, KEY, RedactedValue.class);
        redactingValue.restore();
        redactingValue.set(new RedactedValue("secret"));

        assertThat(redactingValue).hasToString("<redacted>");
    }

    @Test
    void renderingAnEmptyRow_yieldsNull() {
        value.restore();

        assertThat(value).hasToString("null");
    }

    /// Stands in for a value that keeps personal data out of its own rendering.
    private record RedactedValue(String secret) implements StringFormattable {
        @Override
        public void formatTo(Appendable appendable) {
            Append.to(appendable, "<redacted>");
        }
    }
}
