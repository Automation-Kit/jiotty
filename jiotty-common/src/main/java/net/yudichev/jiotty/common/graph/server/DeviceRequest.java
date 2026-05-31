package net.yudichev.jiotty.common.graph.server;

import org.jspecify.annotations.Nullable;

public record DeviceRequest<T>(String name, boolean sent, @Nullable String failure, @Nullable T payload) {
    public DeviceRequest(String name, T payload) {
        this(name, false, null, payload);
    }

    public DeviceRequest(String name) {
        this(name, null);
    }

    public DeviceRequest<T> asSent() {
        return new DeviceRequest<>(name, true, null, payload);
    }

    public DeviceRequest<T> asFailed(String failure) {
        return new DeviceRequest<>(name, sent, failure, payload);
    }

    @Override
    public String toString() {
        var sb = new StringBuilder(64).append("Request{").append(name).append(", sent=").append(sent);
        if (payload != null) {
            sb.append(", payload=").append(payload);
        }
        if (failure != null) {
            sb.append(" FAILED:").append(failure);
        }
        sb.append('}');
        return sb.toString();
    }
}
