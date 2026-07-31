package net.yudichev.jiotty.common.misc;

/// Notified about the health of one upstream a component depends on — an external API, a broker, a database — so a host application can surface intermittent,
/// non-fatal failures. [#onFailure] fires when calls have been failing long enough to count as a sustained outage; [#onSuccess] fires once they recover.
///
/// A handler belongs to the shared component that issues the calls, so an outage is observed once no matter how many callers the component serves.
///
/// @implSpec Implementations must be safe to call from any thread and must be fast: a connector typically reports from whichever thread completed the call,
/// and one handler instance serves every caller the component has.
/// @implNote Typical implementations raise an operator alert or emit a log line.
public interface UpstreamHealthHandler {
    /// A handler that ignores every status change, for hosts that do not surface upstream health.
    UpstreamHealthHandler NO_OP = new UpstreamHealthHandler() {
        @Override
        public void onFailure(String message, Throwable cause) {
            // no-op
        }

        @Override
        public void onSuccess() {
            // no-op
        }

        @Override
        public String toString() {
            return "UpstreamHealthHandler.NO_OP";
        }
    };

    /// Called when calls to the upstream are in sustained failure.
    ///
    /// @param message human-readable description of the failure
    void onFailure(String message, Throwable cause);

    /// Called when a call to the upstream succeeds, clearing any outstanding failure.
    void onSuccess();
}
