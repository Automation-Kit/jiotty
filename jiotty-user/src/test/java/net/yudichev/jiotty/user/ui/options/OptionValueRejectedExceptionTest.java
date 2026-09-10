package net.yudichev.jiotty.user.ui.options;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

final class OptionValueRejectedExceptionTest {
    /// It is an [IllegalArgumentException] so a caller that set the value itself keeps classifying it as a bad argument rather than a server fault.
    @Test
    void isAnIllegalArgument() {
        assertThat(OptionRejection.of(OptionRejectionReasons.INVALID_VALUE).toException()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void messageNamesTheCaseAndItsParameters() {
        assertThat(OptionRejection.mustBeAtLeast(3).toException()).hasMessage("MUST_BE_AT_LEAST {min=3}");
        assertThat(OptionRejection.of(OptionRejectionReasons.NOT_A_NUMBER).toException()).hasMessage("NOT_A_NUMBER");
    }

    @Test
    void exposesTheRejectionItCarries() {
        var exception = OptionRejection.mustBeAtMost(9).toException();

        assertThat(exception.reason()).isEqualTo(OptionRejectionReasons.MUST_BE_AT_MOST);
        assertThat(exception.params()).isEqualTo(Map.of(OptionRejection.PARAM_MAX, 9));
    }
}
