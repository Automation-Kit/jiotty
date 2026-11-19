package net.yudichev.jiotty.energy.octopus;

import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import net.yudichev.jiotty.connector.octopusenergy.Tariff;
import org.immutables.value.Value;

import java.util.List;

/// One electricity supply point. Its MPAN and meter serials identify the customer's supply, so [Object#toString()] redacts them.
@Value.Immutable
@PublicImmutablesStyle
interface BaseMeterPoint {
    /// The Meter Point Administration Number; its supply region is encoded in the trailing letter of each [Tariff#tariffCode()].
    @Value.Redacted
    String mpan();

    /// The serial numbers of the physical meters at this point, one per meter; consumption is fetched per `(mpan, serial)`.
    @Value.Redacted
    List<String> meterSerials();

    /// The tariff agreements at this point over time, in the order the account reports them.
    List<Tariff> tariffs();

    /// Whether this is an export point (energy sent to the grid, e.g. solar / SEG) rather than an import (consumption) point; consumers accounting for what
    /// the user *consumes* should skip export points.
    boolean export();
}
