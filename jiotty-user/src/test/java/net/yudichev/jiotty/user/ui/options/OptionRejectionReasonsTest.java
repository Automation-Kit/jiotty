package net.yudichev.jiotty.user.ui.options;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the reasons as they go on the wire: a client matches these strings to choose its wording, so a rename here leaves it silently showing the generic
/// refusal instead. [Set#of] also rejects two constants that resolve to the same reason.
final class OptionRejectionReasonsTest {
    @Test
    void reasonsAreTheNamesAClientMatchesOn() {
        assertThat(Set.of(OptionRejectionReasons.INVALID_VALUE,
                          OptionRejectionReasons.MISSING_OPTION_NAME,
                          OptionRejectionReasons.UNKNOWN_OPTION,
                          OptionRejectionReasons.NOT_A_NUMBER,
                          OptionRejectionReasons.MUST_BE_AT_LEAST,
                          OptionRejectionReasons.MUST_BE_AT_MOST,
                          OptionRejectionReasons.INVALID_TIME,
                          OptionRejectionReasons.INVALID_DURATION,
                          OptionRejectionReasons.INVALID_LOCATION))
                .containsExactlyInAnyOrder("INVALID_VALUE",
                                           "MISSING_OPTION_NAME",
                                           "UNKNOWN_OPTION",
                                           "NOT_A_NUMBER",
                                           "MUST_BE_AT_LEAST",
                                           "MUST_BE_AT_MOST",
                                           "INVALID_TIME",
                                           "INVALID_DURATION",
                                           "INVALID_LOCATION");
    }
}
