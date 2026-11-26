package net.yudichev.jiotty.common.graph.server;

import net.yudichev.jiotty.common.graph.Graph;
import net.yudichev.jiotty.common.graph.Node;

public interface ServerNode extends Node {
    /// Registers this node in its runner's graph with [Graph#registerNode], so that the next wave calls [Node#wave()] on this node.
    void registerInGraph();

    void logState(String when);
}
