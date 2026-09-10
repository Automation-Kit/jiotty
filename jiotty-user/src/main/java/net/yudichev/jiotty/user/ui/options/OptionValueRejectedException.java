package net.yudichev.jiotty.user.ui.options;

import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;

/// How an [OptionRejection] reaches code that set an option value directly rather than submitting a form. A form submission answers with
/// [FormSubmitResult.Rejected] instead — there the value is the user's to correct, whereas here it came from the server itself and is a fault.
///
/// The message names the case and its bounds only; a value an option refused is never quoted, in a response or in a log.
public final class OptionValueRejectedException extends IllegalArgumentException {
    private final String reason;
    private final Map<String, Object> params;

    OptionValueRejectedException(OptionRejection rejection) {
        super(rejection.params().isEmpty() ? rejection.reason() : rejection.reason() + ' ' + rejection.params());
        checkNotNull(rejection, "rejection");
        reason = rejection.reason();
        params = rejection.params();
    }

    /// The case that was rejected, from the vocabulary in [OptionRejectionReasons].
    public String reason() {
        return reason;
    }

    /// The values behind the case — a bound, a limit — as data rather than as formatted text.
    public Map<String, Object> params() {
        return params;
    }
}
