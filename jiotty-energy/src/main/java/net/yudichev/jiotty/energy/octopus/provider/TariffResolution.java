package net.yudichev.jiotty.energy.octopus.provider;

import net.yudichev.jiotty.energy.octopus.OctopusTariffPriceServiceRegistry;
import net.yudichev.jiotty.energy.octopus.TariffPriceSource;

import static com.google.common.base.Preconditions.checkNotNull;

/// How far [OctopusTariffPriceServiceRegistry#forTariff] has resolved where the prices of a tariff come from.
sealed interface TariffResolution {
    record Resolving() implements TariffResolution {
        public static final Resolving INSTANCE = new Resolving();
    }

    record Resolved(TariffPriceSource source) implements TariffResolution {
        public Resolved {
            checkNotNull(source);
        }
    }

    record Failed(Throwable cause) implements TariffResolution {
        public Failed {
            checkNotNull(cause);
        }
    }
}
