package net.yudichev.jiotty.common.metrics;

/// The shared vocabulary of the resource-guard counter every limiter reports refusals on.
///
/// It is one series joined across unrelated features and repositories, so the name and the `outcome` values have to agree exactly: a guard reporting under a
/// spelling of its own is a guard no dashboard panel or alert rule sees, and nothing fails to say so.
public final class GuardMetrics {
    /// Refusals by a resource guard, labelled `guard` (which limiter) and `outcome` (why it refused).
    public static final String REJECTED_COUNTER = "guard_rejected_total";

    /// {@value} `outcome`: the caller is already at its allowance for the period.
    public static final String OUTCOME_QUOTA_EXCEEDED = "quota_exceeded";

    /// {@value} `outcome`: the caller is asking faster than the limiter admits.
    public static final String OUTCOME_RATE_LIMITED = "rate_limited";

    /// {@value} `outcome`: the work behind the guard is already running at its concurrency limit.
    public static final String OUTCOME_VERIFY_SATURATED = "verify_saturated";

    private GuardMetrics() {
    }
}
