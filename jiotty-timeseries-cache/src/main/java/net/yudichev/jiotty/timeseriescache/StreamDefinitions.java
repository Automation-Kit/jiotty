package net.yudichev.jiotty.timeseriescache;

import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.timeseriescache.TimeSeriesCache.Scope;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/// The resolution and type each `(streamId, scope)` was first defined with, against which a [TimeSeriesCache] refuses a conflicting redefinition.
final class StreamDefinitions {
    /// Concurrent because every consumer of a cache defines its streams on its own thread.
    private final Map<StreamKey, StreamDefinition> definitionsByKey = new ConcurrentHashMap<>();

    /// Records `resolution` and `type` for `(streamId, scope)` unless a definition is already recorded.
    ///
    /// @throws IllegalArgumentException if the recorded definition has a different `resolution` or `type`
    public void register(String streamId, Scope scope, Resolution resolution, TypeToken<?> type) {
        StreamDefinition definition = definitionsByKey.computeIfAbsent(new StreamKey(streamId, scope), _ -> new StreamDefinition(resolution, type));
        checkArgument(definition.resolution().equals(resolution),
                      "stream '%s' for scope %s already defined with resolution %s; conflicting redefinition with %s",
                      streamId, scope, definition.resolution(), resolution);
        checkArgument(definition.type().getType().equals(type.getType()),
                      "stream '%s' for scope %s already defined with type %s; conflicting redefinition with %s",
                      streamId, scope, definition.type(), type);
    }

    public void forgetScope(Scope scope) {
        definitionsByKey.keySet().removeIf(key -> key.scope().equals(scope));
    }

    public void forgetStream(String streamId) {
        definitionsByKey.keySet().removeIf(key -> key.streamId().equals(streamId));
    }

    private record StreamKey(String streamId, Scope scope) {
        private StreamKey {
            checkNotNull(streamId, "streamId");
            checkNotNull(scope, "scope");
        }
    }

    private record StreamDefinition(Resolution resolution, TypeToken<?> type) {
        private StreamDefinition {
            checkNotNull(resolution, "resolution");
            checkNotNull(type, "type");
        }
    }
}
