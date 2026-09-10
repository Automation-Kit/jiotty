package net.yudichev.jiotty.user.ui.options;

/// The rejection reasons this module itself raises, and the generic ones an application's own options reuse. A client matches on these and supplies its own
/// wording; an application needing a reason not listed here declares its own constant and passes it to [OptionValueRejectedException].
public final class OptionRejectionReasons {
    /// Stands for every failure the server did not name, so it carries no parameters and no detail.
    public static final String INVALID_VALUE = "INVALID_VALUE";

    public static final String MISSING_OPTION_NAME = "MISSING_OPTION_NAME";

    public static final String UNKNOWN_OPTION = "UNKNOWN_OPTION";

    public static final String NOT_A_NUMBER = "NOT_A_NUMBER";

    /// The minimum is the `min` parameter.
    public static final String MUST_BE_AT_LEAST = "MUST_BE_AT_LEAST";

    /// The maximum is the `max` parameter.
    public static final String MUST_BE_AT_MOST = "MUST_BE_AT_MOST";

    public static final String INVALID_TIME = "INVALID_TIME";

    public static final String INVALID_DURATION = "INVALID_DURATION";

    public static final String INVALID_LOCATION = "INVALID_LOCATION";

    private OptionRejectionReasons() {
    }
}
