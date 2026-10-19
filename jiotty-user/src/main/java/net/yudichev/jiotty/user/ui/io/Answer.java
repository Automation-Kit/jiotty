package net.yudichev.jiotty.user.ui.io;

import com.google.common.collect.ImmutableMap;

import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.rest.HttpStatuses.isValid;

/// Data encapsulating a single answer to an HTTP request.
///
/// @param status  the HTTP status
/// @param headers headers beyond the ones `body` sets for itself
/// @param body    the body, [ResponseBody#EMPTY] for a status alone
public record Answer(int status, Map<String, String> headers, ResponseBody body) {
    public Answer {
        checkArgument(isValid(status), "status out of range: %s", status);
        headers = ImmutableMap.copyOf(headers);
        checkNotNull(body, "body");
    }

    public static Answer of(int status, ResponseBody body) {
        return new Answer(status, ImmutableMap.of(), body);
    }

    public static Answer of(int status) {
        return of(status, ResponseBody.EMPTY);
    }

    public Answer withHeader(String name, String value) {
        ImmutableMap<String, String> extendedHeaders = ImmutableMap.<String, String>builderWithExpectedSize(headers.size() + 1)
                                                                   .putAll(headers)
                                                                   .put(name, value)
                                                                   .buildKeepingLast();
        return new Answer(status, extendedHeaders, body);
    }
}
