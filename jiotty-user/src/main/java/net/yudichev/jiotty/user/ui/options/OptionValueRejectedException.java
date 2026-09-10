package net.yudichev.jiotty.user.ui.options;

import com.google.common.collect.ImmutableMap;
import org.jspecify.annotations.Nullable;

import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;

/// Rejects a submitted option value, naming the case rather than describing it, so no response carries a sentence frozen in one language. The message and the
/// cause are for the server log only; neither reaches a response.
public final class OptionValueRejectedException extends IllegalArgumentException {
    /// The bound a value fell short of, as [#params] carries it to the client.
    public static final String PARAM_MIN = "min";
    /// The bound a value exceeded, as [#params] carries it to the client.
    public static final String PARAM_MAX = "max";

    private final String reason;
    private final ImmutableMap<String, Object> params;

    private OptionValueRejectedException(String reason, ImmutableMap<String, Object> params, @Nullable Throwable cause) {
        super(params.isEmpty() ? reason : reason + ' ' + params, cause);
        this.reason = checkNotNull(reason);
        this.params = checkNotNull(params);
    }

    public static OptionValueRejectedException of(String reason) {
        return new OptionValueRejectedException(reason, ImmutableMap.of(), null);
    }

    public static OptionValueRejectedException of(String reason, Map<String, ?> params) {
        return new OptionValueRejectedException(reason, ImmutableMap.copyOf(params), null);
    }

    public static OptionValueRejectedException of(String reason, Throwable cause) {
        return new OptionValueRejectedException(reason, ImmutableMap.of(), cause);
    }

    public static OptionValueRejectedException notANumber(Throwable cause) {
        return of(OptionRejectionReasons.NOT_A_NUMBER, cause);
    }

    public static OptionValueRejectedException mustBeAtLeast(Object min) {
        return of(OptionRejectionReasons.MUST_BE_AT_LEAST, ImmutableMap.of(PARAM_MIN, min));
    }

    public static OptionValueRejectedException mustBeAtMost(Object max) {
        return of(OptionRejectionReasons.MUST_BE_AT_MOST, ImmutableMap.of(PARAM_MAX, max));
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
