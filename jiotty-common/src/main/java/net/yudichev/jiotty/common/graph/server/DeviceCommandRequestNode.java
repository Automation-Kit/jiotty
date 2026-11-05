package net.yudichev.jiotty.common.graph.server;

import org.jspecify.annotations.Nullable;

public interface DeviceCommandRequestNode<R> extends ServerNode {
    // more readable
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    boolean requestPending();

    @SuppressWarnings("BooleanMethodIsAlwaysInverted") // more readable
    @Nullable DeviceRequest<R> currentRequest();
}
