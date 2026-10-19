package net.yudichev.jiotty.common.metrics;

/// The shared vocabulary of the resource-guard counter every limiter reports refusals on.
///
/// It is one series joined across unrelated features and repositories, so the name and the {@value #TAG_OUTCOME} values have to agree exactly: a guard
/// reporting under a spelling of its own is a guard no dashboard panel or alert rule sees, and nothing fails to say so.
public final class GuardMetrics {
    /// Refusals by a resource guard, labelled {@value #TAG_GUARD} (which limiter) and {@value #TAG_OUTCOME} (why it refused).
    public static final String REJECTED_COUNTER = "guard_rejected_total";

    /// The [#REJECTED_COUNTER] tag naming the limiter that refused.
    public static final String TAG_GUARD = "guard";

    /// The [#REJECTED_COUNTER] tag naming why the limiter refused, one of the `OUTCOME_` values.
    public static final String TAG_OUTCOME = "outcome";

    /// {@value} {@value #TAG_OUTCOME}: the caller is already at its allowance for the period.
    public static final String OUTCOME_QUOTA_EXCEEDED = "quota_exceeded";

    /// {@value} {@value #TAG_OUTCOME}: the caller is asking faster than the limiter admits.
    public static final String OUTCOME_RATE_LIMITED = "rate_limited";

    /// {@value} {@value #TAG_OUTCOME}: the work behind the guard is already running at its concurrency limit.
    public static final String OUTCOME_VERIFY_SATURATED = "verify_saturated";

    /// {@value} {@value #TAG_OUTCOME}: the whole service already holds as many of the guarded resource as it serves.
    public static final String OUTCOME_AT_CAPACITY = "at_capacity";

    /// {@value} {@value #TAG_OUTCOME}: the caller already holds as many of the guarded resource as one user may.
    public static final String OUTCOME_USER_AT_CAPACITY = "user_at_capacity";

    private GuardMetrics() {
    }
}
