package net.yudichev.jiotty.common.graph.server;

import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.StringFormattable;
import org.jspecify.annotations.Nullable;

public record DeviceRequest<T>(String name, boolean sent, @Nullable String failure, @Nullable T payload) implements StringFormattable {
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
        return toString(64);
    }

    @Override
    public void formatTo(Appendable appendable) {
        Append.to(appendable, "Request{");
        Append.to(appendable, name);
        Append.to(appendable, ", sent=");
        Append.to(appendable, sent);
        if (payload != null) {
            Append.to(appendable, ", payload=");
            Append.to(appendable, payload);
        }
        if (failure != null) {
            Append.to(appendable, " FAILED:");
            Append.to(appendable, failure);
        }
        Append.to(appendable, '}');
    }
}
