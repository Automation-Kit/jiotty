package net.yudichev.jiotty.energy;

import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;

/// A minimal view of an Octopus account: its electricity meter points, each with its meters and tariff history. The values are Octopus/UK identifiers — MPAN,
/// meter serial, and Octopus tariff codes.
///
/// @param meterPoints the account's electricity meter points
public record OctopusAccountDetails(List<MeterPoint> meterPoints) {
    public OctopusAccountDetails {
        meterPoints = ImmutableList.copyOf(meterPoints);
    }

    /// One electricity supply point.
    ///
    /// @param mpan          the Meter Point Administration Number; its supply region is encoded in the trailing letter of each [TariffPeriod#tariffCode]
    /// @param meterSerials  the serial numbers of the physical meters at this point (one per meter; consumption is fetched per `(mpan, serial)`)
    /// @param tariffPeriods the tariff agreements at this point over time, in the order the account reports them
    public record MeterPoint(String mpan, List<String> meterSerials, List<TariffPeriod> tariffPeriods) {
        public MeterPoint {
            checkNotNull(mpan, "mpan");
            meterSerials = ImmutableList.copyOf(meterSerials);
            tariffPeriods = ImmutableList.copyOf(tariffPeriods);
        }
    }

    /// A tariff agreement over a half-open `[validFrom, validTo)` window.
    ///
    /// @param tariffCode the Octopus tariff code (e.g. `E-1R-AGILE-23-12-06-A`); the trailing letter is the supply region
    /// @param validFrom  the instant the agreement starts (inclusive)
    /// @param validTo    the instant the agreement ends (exclusive)
    public record TariffPeriod(String tariffCode, Instant validFrom, Instant validTo) {
        public TariffPeriod {
            checkNotNull(tariffCode, "tariffCode");
            checkNotNull(validFrom, "validFrom");
            checkNotNull(validTo, "validTo");
        }
    }
}
