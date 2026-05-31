package net.yudichev.jiotty.common.graph;


import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;

public abstract class BaseNode implements Node {
    private @Nullable List<Node> subscriptionBuffer;
    private NodeContext nodeContext;

    public final String name() {
        return nodeContext.name();
    }

    @Override
    public final void initialise(NodeContext nodeContext) {
        this.nodeContext = nodeContext;
        if (subscriptionBuffer != null) {
            subscriptionBuffer.forEach(nodeContext::subscribeTo);
            subscriptionBuffer = null;
        }
        initialise();
    }

    public final <T extends Node> T subscribeTo(T node) {
        if (nodeContext == null) {
            if (subscriptionBuffer == null) {
                subscriptionBuffer = new ArrayList<>();
            }
            subscriptionBuffer.add(node);
        } else {
            nodeContext.subscribeTo(node);
        }
        return node;
    }

    public final NodeContext nodeContext() {
        return checkNotNull(nodeContext, "not initialised yet");
    }

    public boolean trigger() {
        return nodeContext.triggerInNextWave();
    }

    public boolean triggerMeAndParents() {
        return nodeContext.triggerMeAndParentsInNextWave();
    }

    protected void initialise() {
    }

    protected final Graph graph() {
        return nodeContext.graph();
    }

    protected final Instant waveTime() {
        return nodeContext.graph().waveTime();
    }
}
