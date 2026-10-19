package net.yudichev.jiotty.common.lang;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.concurrent.atomic.AtomicInteger;

import static net.yudichev.jiotty.common.lang.AtomicIntegers.tryIncrementBelow;
import static org.assertj.core.api.Assertions.assertThat;

class AtomicIntegersTest {
    /// A counter below the limit is incremented; one at the limit is left as it is.
    @ParameterizedTest
    @CsvSource({
            "1, true, 2",
            "2, false, 2",
    })
    void incrementsACounterOnlyBelowTheLimit(int initialValue, boolean expectedIncremented, int expectedValue) {
        var counter = new AtomicInteger(initialValue);

        assertThat(tryIncrementBelow(counter, 2)).isEqualTo(expectedIncremented);
        assertThat(counter.get()).isEqualTo(expectedValue);
    }
}
