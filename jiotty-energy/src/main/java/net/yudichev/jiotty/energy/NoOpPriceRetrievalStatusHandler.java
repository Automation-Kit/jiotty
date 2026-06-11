package net.yudichev.jiotty.energy;

/// Default [PriceRetrievalStatusHandler] that ignores all status changes. Used when a host application does not want to surface price retrieval health.
public final class NoOpPriceRetrievalStatusHandler implements PriceRetrievalStatusHandler {
    @Override
    public void onFailure(String message, Throwable cause) {
        // no-op
    }

    @Override
    public void onSuccess() {
        // no-op
    }
}
