package net.yudichev.jiotty.energy.octopus.provider;

import net.yudichev.jiotty.common.graph.server.ServerNode;
import org.jspecify.annotations.Nullable;

/// A node that fetches the Octopus account at each poll.
interface AccountNode extends ServerNode {
    /// @return the account as the latest successful fetch returned it, or, until a fetch has succeeded, the latest failure; `null` until a fetch has ended
    @Nullable AccountState state();
}
