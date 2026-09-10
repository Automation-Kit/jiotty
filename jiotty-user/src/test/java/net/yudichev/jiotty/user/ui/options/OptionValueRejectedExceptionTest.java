package net.yudichev.jiotty.user.ui.options;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class OptionValueRejectedExceptionTest {
    @Test
    void carriesTheCaseAndNothingElse() {
        var rejection = OptionValueRejectedException.of(OptionRejectionReasons.INVALID_TIME);

        assertThat(rejection.reason()).isEqualTo(OptionRejectionReasons.INVALID_TIME);
        assertThat(rejection.params()).isEmpty();
        assertThat(rejection.getCause()).isNull();
    }

    @Test
    void namesTheParameterOfEachBound() {
        assertThat(OptionValueRejectedException.notANumber(new NumberFormatException("x")).reason()).isEqualTo(OptionRejectionReasons.NOT_A_NUMBER);
        assertThat(OptionValueRejectedException.mustBeAtLeast(3).params()).isEqualTo(Map.of("min", 3));
        assertThat(OptionValueRejectedException.mustBeAtMost(9).params()).isEqualTo(Map.of("max", 9));
    }

    @Test
    void keepsTheCauseForTheLog() {
        var cause = new NumberFormatException("For input string: \"abc\"");

        assertThat(OptionValueRejectedException.of(OptionRejectionReasons.INVALID_DURATION, cause)).hasCause(cause);
    }

    /// It is an [IllegalArgumentException] so the option pipeline's existing catch blocks keep classifying it as a bad value rather than a server fault.
    @Test
    void isAnIllegalArgument() {
        assertThat(OptionValueRejectedException.of(OptionRejectionReasons.INVALID_VALUE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void messageNamesTheCaseAndItsParameters() {
        assertThat(OptionValueRejectedException.mustBeAtLeast(3)).hasMessage("MUST_BE_AT_LEAST {min=3}");
        assertThat(OptionValueRejectedException.of(OptionRejectionReasons.NOT_A_NUMBER)).hasMessage("NOT_A_NUMBER");
    }

    @Test
    void takesItsOwnCopyOfTheParameters() {
        var params = new HashMap<String, Object>();
        params.put("min", 1);
        var rejection = OptionValueRejectedException.of("SOME_REASON", params);

        params.put("min", 99);

        assertThat(rejection.params()).isEqualTo(Map.of("min", 1));
        assertThatThrownBy(() -> rejection.params().put("max", 2)).isInstanceOf(UnsupportedOperationException.class);
    }
}
