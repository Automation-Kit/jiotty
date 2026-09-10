package net.yudichev.jiotty.user.ui.options;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class OptionRejectionTest {
    @Test
    void carriesTheCaseAndNothingElse() {
        var rejection = OptionRejection.of(OptionRejectionReasons.INVALID_TIME);

        assertThat(rejection.reason()).isEqualTo(OptionRejectionReasons.INVALID_TIME);
        assertThat(rejection.params()).isEmpty();
    }

    /// A client reads these as raw strings, so the constants are only half the contract: a rename that stayed consistent across every reference here would
    /// still leave the app showing its generic wording for a bound it cannot find.
    @Test
    void parameterNamesAreTheSpellingsAClientReads() {
        assertThat(OptionRejection.PARAM_MIN).isEqualTo("min");
        assertThat(OptionRejection.PARAM_MAX).isEqualTo("max");
    }

    @Test
    void namesTheParameterOfEachBound() {
        assertThat(OptionRejection.notANumber().reason()).isEqualTo(OptionRejectionReasons.NOT_A_NUMBER);
        assertThat(OptionRejection.mustBeAtLeast(3).params()).isEqualTo(Map.of(OptionRejection.PARAM_MIN, 3));
        assertThat(OptionRejection.mustBeAtMost(9).params()).isEqualTo(Map.of(OptionRejection.PARAM_MAX, 9));
    }

    @Test
    void takesItsOwnCopyOfTheParameters() {
        var params = new HashMap<String, Object>();
        params.put("min", 1);
        var rejection = OptionRejection.of("SOME_REASON", params);

        params.put("min", 99);

        assertThat(rejection.params()).isEqualTo(Map.of("min", 1));
        assertThatThrownBy(() -> rejection.params().put("max", 2)).isInstanceOf(UnsupportedOperationException.class);
    }

    /// The route out for a caller that set the value itself: it has no user to hand the refusal back to, so it gets it as a fault.
    @Test
    void becomesAnExceptionCarryingTheSameCase() {
        var exception = OptionRejection.mustBeAtLeast(3).toException();

        assertThat(exception.reason()).isEqualTo(OptionRejectionReasons.MUST_BE_AT_LEAST);
        assertThat(exception.params()).isEqualTo(Map.of(OptionRejection.PARAM_MIN, 3));
    }
}
