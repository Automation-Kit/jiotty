package net.yudichev.jiotty.common.graph;

import net.yudichev.jiotty.common.lang.Closeable;

public interface Node extends Closeable {
    void initialise(NodeContext nodeContext);

    /// Updates this node's state. The graph calls this method in the first wave after [Graph#registerNode], whether or not a parent has changed, and then
    /// in each wave in which a parent reports a change or that follows a call of [NodeContext#triggerInNextWave()] or
    /// [NodeContext#triggerMeAndParentsInNextWave()] for this node.
    ///
    /// @return whether this node's externally visible state, the values that its children can read, changed; `true` makes the graph call this method on
    ///         this node's children in the same wave
    boolean wave();

    default void afterWave() {
    }

    @Override
    default void close() {
    }
}
