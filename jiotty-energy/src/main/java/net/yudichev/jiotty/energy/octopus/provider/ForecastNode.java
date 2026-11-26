package net.yudichev.jiotty.energy.octopus.provider;

import net.yudichev.jiotty.common.graph.server.ServerNode;
import net.yudichev.jiotty.energy.Prices;
import org.jspecify.annotations.Nullable;

import static com.google.common.base.Preconditions.checkNotNull;

/// The price forecast for the supply region of the first agreement in the forward window whose tariff's prices are dynamic, which extends those prices past
/// what Octopus have published.
interface ForecastNode extends ServerNode {
    /// @return the forecast; `null` until the agreements in the forward window, and the resolution of each agreement's tariff, are known
    @Nullable Forecast forecast();

    sealed interface Forecast {
        /// No agreement in the forward window has a tariff with dynamic prices, or no forecast for its region has arrived.
        final class None implements Forecast {
            public static final None INSTANCE = new None();

            private None() {
            }
        }

        record Available(Prices prices) implements Forecast {
            public Available {
                checkNotNull(prices);
            }
        }
    }
}
