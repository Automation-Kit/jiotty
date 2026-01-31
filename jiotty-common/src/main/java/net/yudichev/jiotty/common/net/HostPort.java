package net.yudichev.jiotty.common.net;

import static com.google.common.base.Preconditions.checkArgument;

public record HostPort(String host, int port) {
    public static HostPort parse(String hostPortStr) {
        String[] parts = hostPortStr.split(":");
        checkArgument(parts.length == 2, "Invalid host:port format: %s", hostPortStr);
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);
        return new HostPort(host, port);
    }
}
