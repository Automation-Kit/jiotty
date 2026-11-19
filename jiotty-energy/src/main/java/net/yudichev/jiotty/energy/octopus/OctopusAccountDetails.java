package net.yudichev.jiotty.energy.octopus;

import com.google.common.collect.ImmutableList;

import java.util.List;

/// A minimal view of an Octopus account: its electricity meter points, each with its meters and tariff history. The values are Octopus/UK identifiers — MPAN,
/// meter serial, and Octopus tariff codes.
///
/// @param meterPoints the account's electricity meter points
public record OctopusAccountDetails(List<MeterPoint> meterPoints) {
    public OctopusAccountDetails {
        meterPoints = ImmutableList.copyOf(meterPoints);
    }
}
