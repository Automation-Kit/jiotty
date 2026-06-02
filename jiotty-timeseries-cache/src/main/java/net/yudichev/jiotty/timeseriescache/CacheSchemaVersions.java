package net.yudichev.jiotty.timeseriescache;

import com.google.common.reflect.TypeToken;

import static com.google.common.base.Preconditions.checkArgument;

/// Resolves the [CacheSchemaVersion] of a cached value type. The single place that reads the annotation, so the default (unannotated → version `1`) and the
/// valid range (`[1, 65535]`, bounded by the frame's unsigned 16-bit version field) are enforced once.
final class CacheSchemaVersions {
    static final int MIN_VERSION = 1;
    static final int MAX_VERSION = 0xFFFF;

    private CacheSchemaVersions() {
    }

    static int resolve(TypeToken<?> type) {
        CacheSchemaVersion annotation = type.getRawType().getAnnotation(CacheSchemaVersion.class);
        int version = annotation == null ? MIN_VERSION : annotation.value();
        checkArgument(version >= MIN_VERSION && version <= MAX_VERSION,
                      "@CacheSchemaVersion on %s must be in [%s, %s], was %s", type, MIN_VERSION, MAX_VERSION, version);
        return version;
    }
}
