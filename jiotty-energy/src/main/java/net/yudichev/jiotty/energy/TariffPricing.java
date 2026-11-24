package net.yudichev.jiotty.energy;

/// How the prices of a tariff are known ahead of time.
public enum TariffPricing {
    /// The price changes from one interval to the next and is published a day ahead, with a forecast beyond (Octopus Agile).
    DYNAMIC,
    /// The prices follow rates that the supplier publishes in advance: one price for long stretches (Octopus Flexible), or fixed bands by time of day
    /// (Intelligent Octopus Go).
    PUBLISHED,
    /// No prices are available for this tariff.
    UNSUPPORTED;

    public boolean isDynamic() {
        return switch (this) {
            case DYNAMIC -> true;
            case PUBLISHED, UNSUPPORTED -> false;
        };
    }
}
