package net.yudichev.jiotty.connector.octopusenergy;

import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;
import org.immutables.value.Value.Immutable;

/// Pairs an MPAN with the serial number of one of the meters at that meter point. Returned by [OctopusAccountService#getMpanAndMeter] — an account with N
/// metering installations and M meters per installation produces N×M rows.
@Immutable
@PublicImmutablesStyle
interface BaseMpanAndMeter {
    @Value.Parameter
    @Value.Redacted
    String mpan();

    @Value.Parameter
    @Value.Redacted
    String meterSerial();
}
