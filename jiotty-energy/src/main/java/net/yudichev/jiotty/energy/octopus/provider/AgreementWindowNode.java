package net.yudichev.jiotty.energy.octopus.provider;

import net.yudichev.jiotty.common.graph.server.ServerNode;
import net.yudichev.jiotty.connector.octopusenergy.Tariff;
import org.jspecify.annotations.Nullable;

import java.util.List;

/// The agreements in the forward window, which starts at the wave time and has a fixed length.
interface AgreementWindowNode extends ServerNode {
    /// @return the agreements of the first property's first import meter point that overlap the forward window, in the order of their start; `null` until
    ///         the account has been fetched
    @Nullable List<Tariff> agreements();
}
