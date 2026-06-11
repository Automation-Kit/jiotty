package net.yudichev.jiotty.energy;

/// Notified about the health of background price retrieval so a host application can surface intermittent, non-fatal failures. [#onFailure] fires when
/// retrieval has been failing long enough to count as a sustained outage; [#onSuccess] fires once it recovers.
///
/// All methods are invoked on the single price-retrieval scheduling thread, so implementations need not be thread-safe.
///
/// @implNote Typical implementations raise an operator alert or emit a log line.
public interface PriceRetrievalStatusHandler {
    /// Called when price retrieval is in sustained failure.
    ///
    /// @param message human-readable description of the failure
    /// @param cause   the failure
    void onFailure(String message, Throwable cause);

    /// Called when price retrieval succeeds, clearing any outstanding failure.
    void onSuccess();
}
