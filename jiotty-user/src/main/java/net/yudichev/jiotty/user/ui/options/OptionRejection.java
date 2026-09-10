package net.yudichev.jiotty.user.ui.options;

import com.google.common.collect.ImmutableMap;

import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;

/// A submitted value an option refused, naming the case rather than describing it, so no response carries a sentence frozen in one language.
///
/// Nothing here is derived from what was submitted: an option value carries whatever the user typed into the form, up to a third-party account password, so a
/// rejection that quoted it would put it into a response and a log line alike.
///
/// @param reason the case, from the vocabulary in [OptionRejectionReasons]
/// @param params the values behind the case — a bound, a limit — as data rather than as formatted text
public record OptionRejection(String reason, Map<String, Object> params) {
    /// The bound a value fell short of.
    public static final String PARAM_MIN = "min";
    /// The bound a value exceeded.
    public static final String PARAM_MAX = "max";

    public OptionRejection {
        checkNotNull(reason, "reason");
        params = ImmutableMap.copyOf(params);
    }

    public static OptionRejection of(String reason) {
        return new OptionRejection(reason, ImmutableMap.of());
    }

    public static OptionRejection of(String reason, Map<String, ?> params) {
        return new OptionRejection(reason, ImmutableMap.copyOf(params));
    }


    public static OptionRejection notANumber() {
        return of(OptionRejectionReasons.NOT_A_NUMBER);
    }

    public static OptionRejection mustBeAtLeast(Object min) {
        return of(OptionRejectionReasons.MUST_BE_AT_LEAST, ImmutableMap.of(PARAM_MIN, min));
    }

    public static OptionRejection mustBeAtMost(Object max) {
        return of(OptionRejectionReasons.MUST_BE_AT_MOST, ImmutableMap.of(PARAM_MAX, max));
    }

    /// How this refusal reaches a caller that set the value itself rather than submitting a form — for which a value the option refuses is a fault, not
    /// something to report back.
    public OptionValueRejectedException toException() {
        return new OptionValueRejectedException(this);
    }
}
